package com.kakao.taxi.liveupdate

import android.Manifest
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.Locale

/** Kinds the connected-devices capsule can tell apart (tier 1). */
internal enum class ConnectedDeviceKind {
    WATCH,
    BUDS,
    BAND,
    RING,
    GLASSES,
    HEADPHONES,
    SPEAKER,
    PHONE,
    GENERIC
}

internal data class ConnectedDeviceInfo(
    val address: String,
    val name: String?,
    val kind: ConnectedDeviceKind,
    // Tier 2 (round 85): the Bluetooth stack's own battery value
    // for this device when it has one (0..100); null when the
    // stack reports nothing (e.g. the owner's BR/EDR Watch FE).
    // Part of the data class on purpose: the listener re-pushes
    // the capsule when the scanned list changes, so a battery
    // change alone refreshes the capsule.
    val batteryLevel: Int? = null
)

/**
 * Tier 1 of the connected-devices capsule: the connection state
 * comes from the Bluetooth stack itself, not from notifications —
 * several Galaxy accessories (the owner's Watch on One UI 9 among
 * them) never post a notification at all, so the upstream
 * notification-text rule can never see them. Only devices the
 * Bluetooth stack reports as connected are considered, so
 * transient BLE encounters nearby cannot
 * flicker the capsule. Name/class reads need BLUETOOTH_CONNECT on
 * Android 12+; without it the scan degrades to an empty list
 * instead of throwing.
 */
internal object ConnectedDeviceScanner {

    // Same tag as the listener so one logcat filter catches both.
    private const val TAG = "LiveUpdateListener"

    // Classic-profile proxies (round 73). BluetoothManager's
    // getConnectedDevices/getConnectionState only support GATT;
    // HEADSET and A2DP — the profiles a Galaxy Watch (Handsfree)
    // and earbuds actually connect on — are readable only through
    // these asynchronously bound proxies.
    @Volatile
    private var headsetProxy: BluetoothHeadset? = null

    @Volatile
    private var a2dpProxy: BluetoothA2dp? = null

    @Volatile
    private var profileProxiesRequested = false

    private val profileServiceListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            when (profile) {
                BluetoothProfile.HEADSET -> headsetProxy = proxy as? BluetoothHeadset
                BluetoothProfile.A2DP -> a2dpProxy = proxy as? BluetoothA2dp
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            when (profile) {
                BluetoothProfile.HEADSET -> headsetProxy = null
                BluetoothProfile.A2DP -> a2dpProxy = null
            }
            profileProxiesRequested = false
        }
    }

    private fun ensureProfileProxies(context: Context, adapter: BluetoothAdapter) {
        if (profileProxiesRequested) {
            return
        }
        val appContext = context.applicationContext
        val headsetOk = runCatching {
            adapter.getProfileProxy(appContext, profileServiceListener, BluetoothProfile.HEADSET)
        }.getOrDefault(false)
        val a2dpOk = runCatching {
            adapter.getProfileProxy(appContext, profileServiceListener, BluetoothProfile.A2DP)
        }.getOrDefault(false)
        profileProxiesRequested = headsetOk || a2dpOk
    }

    fun releaseProfileProxies(context: Context) {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
            ?.adapter
            ?: return
        headsetProxy?.let { proxy ->
            runCatching { adapter.closeProfileProxy(BluetoothProfile.HEADSET, proxy) }
        }
        a2dpProxy?.let { proxy ->
            runCatching { adapter.closeProfileProxy(BluetoothProfile.A2DP, proxy) }
        }
        headsetProxy = null
        a2dpProxy = null
        profileProxiesRequested = false
    }

    fun hasBluetoothConnectPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return true
        }
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun scan(context: Context): List<ConnectedDeviceInfo> {
        val manager =
            context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                ?: return emptyList()
        val adapter = manager.adapter ?: return emptyList()
        val bluetoothOn = runCatching { adapter.isEnabled }.getOrDefault(false)
        val permission = hasBluetoothConnectPermission(context)
        if (!bluetoothOn || !permission) {
            Log.i(
                TAG,
                "Device scan unavailable: btOn=$bluetoothOn bluetoothConnectPermission=$permission"
            )
            return emptyList()
        }
        ensureProfileProxies(context, adapter)
        val gattDevices = runCatching {
            manager.getConnectedDevices(BluetoothProfile.GATT)
        }.getOrDefault(emptyList())
        val headsetDevices = headsetProxy?.let { proxy ->
            runCatching { proxy.connectedDevices }.getOrDefault(emptyList())
        }.orEmpty()
        val a2dpDevices = a2dpProxy?.let { proxy ->
            runCatching { proxy.connectedDevices }.getOrDefault(emptyList())
        }.orEmpty()
        val byAddress = linkedMapOf<String, BluetoothDevice>()
        for (device in gattDevices + headsetDevices + a2dpDevices) {
            byAddress[device.address] = device
        }
        val result = byAddress.values
            .map { device ->
                val rawName = runCatching { device.name }
                    .getOrNull()
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                // Display prefers the user-set alias ("Watch FE
                // của Chí Cường") over the raw Bluetooth name
                // ("Galaxy Watch FE (G0RV)"); classification keeps
                // using the raw name, whose keywords are the
                // reliable signal (round 74).
                val alias = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    runCatching { device.alias }
                        .getOrNull()
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                } else {
                    null
                }
                ConnectedDeviceInfo(
                    address = device.address,
                    name = alias ?: rawName,
                    kind = classify(
                        name = rawName,
                        bluetoothClass = runCatching { device.bluetoothClass }.getOrNull()
                    ),
                    batteryLevel = DeviceBatteryReader.read(device)
                )
            }
            .sortedWith(
                compareBy<ConnectedDeviceInfo> { it.kind.ordinal }
                    .thenBy { it.name.orEmpty() }
            )
        // Diagnostics: a found set on change; when empty, the
        // per-source counts and proxy binding state, so a blind
        // source is visible at a glance.
        if (result.isNotEmpty()) {
            Log.i(
                TAG,
                "Device scan found: " + result.joinToString { info ->
                    "${info.name ?: "?"}(${info.kind}" +
                        (info.batteryLevel?.let { ", $it%" } ?: "") + ")"
                }
            )
        } else {
            Log.i(
                TAG,
                "Device scan empty; sources: gatt=${gattDevices.size} " +
                    "headset=${headsetDevices.size} " +
                    "(proxy ${if (headsetProxy != null) "bound" else "pending"}) " +
                    "a2dp=${a2dpDevices.size} " +
                    "(proxy ${if (a2dpProxy != null) "bound" else "pending"})"
            )
        }
        return result
    }

    fun classify(
        name: String?,
        bluetoothClass: BluetoothClass?
    ): ConnectedDeviceKind {
        val normalized = name?.lowercase(Locale.ROOT).orEmpty()
        when {
            normalized.contains("buds") -> return ConnectedDeviceKind.BUDS
            normalized.contains("ring") -> return ConnectedDeviceKind.RING
            normalized.contains("glass") -> return ConnectedDeviceKind.GLASSES
            normalized.contains("fit") -> return ConnectedDeviceKind.BAND
            normalized.contains("watch") -> return ConnectedDeviceKind.WATCH
        }
        val deviceClass = bluetoothClass?.deviceClass ?: return ConnectedDeviceKind.GENERIC
        return when (deviceClass) {
            BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES,
            BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE,
            BluetoothClass.Device.AUDIO_VIDEO_PORTABLE_AUDIO -> ConnectedDeviceKind.HEADPHONES
            BluetoothClass.Device.AUDIO_VIDEO_LOUDSPEAKER,
            BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO -> ConnectedDeviceKind.SPEAKER
            else -> when (bluetoothClass.majorDeviceClass) {
                BluetoothClass.Device.Major.WEARABLE -> ConnectedDeviceKind.WATCH
                BluetoothClass.Device.Major.HEALTH -> ConnectedDeviceKind.BAND
                BluetoothClass.Device.Major.AUDIO_VIDEO -> ConnectedDeviceKind.HEADPHONES
                BluetoothClass.Device.Major.PHONE -> ConnectedDeviceKind.PHONE
                else -> ConnectedDeviceKind.GENERIC
            }
        }
    }
}
