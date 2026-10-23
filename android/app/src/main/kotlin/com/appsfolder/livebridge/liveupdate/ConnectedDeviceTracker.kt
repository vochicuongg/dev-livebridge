package com.kakao.taxi.liveupdate

import android.Manifest
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
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
    GENERIC
}

internal data class ConnectedDeviceInfo(
    val address: String,
    val name: String?,
    val kind: ConnectedDeviceKind
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

    private val CONNECTION_PROFILES = intArrayOf(
        BluetoothProfile.GATT,
        BluetoothProfile.HEADSET,
        BluetoothProfile.A2DP
    )

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
        if (!adapter.isEnabled) {
            return emptyList()
        }
        if (!hasBluetoothConnectPermission(context)) {
            return emptyList()
        }
        val byAddress = linkedMapOf<String, BluetoothDevice>()
        for (profile in CONNECTION_PROFILES) {
            runCatching { manager.getConnectedDevices(profile) }
                .getOrDefault(emptyList())
                .forEach { device -> byAddress[device.address] = device }
        }
        // Bonded sweep for anything the profile queries missed.
        // BluetoothDevice.isConnected() is a hidden API, so the
        // state check goes through BluetoothManager instead.
        runCatching { adapter.bondedDevices }
            .getOrNull()
            .orEmpty()
            .forEach { device ->
                if (device.address in byAddress) {
                    return@forEach
                }
                val connected = CONNECTION_PROFILES.any { profile ->
                    runCatching { manager.getConnectionState(device, profile) }
                        .getOrDefault(BluetoothProfile.STATE_DISCONNECTED) ==
                        BluetoothProfile.STATE_CONNECTED
                }
                if (connected) {
                    byAddress[device.address] = device
                }
            }
        return byAddress.values
            .map { device ->
                val name = runCatching { device.name }
                    .getOrNull()
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                ConnectedDeviceInfo(
                    address = device.address,
                    name = name,
                    kind = classify(
                        name = name,
                        bluetoothClass = runCatching { device.bluetoothClass }.getOrNull()
                    )
                )
            }
            .sortedWith(
                compareBy<ConnectedDeviceInfo> { it.kind.ordinal }
                    .thenBy { it.name.orEmpty() }
            )
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
                else -> ConnectedDeviceKind.GENERIC
            }
        }
    }
}
