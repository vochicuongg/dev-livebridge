package com.kakao.taxi.liveupdate

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.IBinder
import android.util.Log
import java.util.UUID

/**
 * Round 89 (final watch-battery discovery): round 86 only
 * fetched binder services whose names contain batt/sem/
 * deviceinfo, so a service registered under another name
 * (wearable, device_info, ...) would have slipped through.
 * This probe closes the remaining doors in one pass:
 *  1. dumps EVERY binder service name (chunked) and fetches
 *     interface descriptors for a wide net of candidates;
 *  2. tries a direct GATT connection to the watch and lists
 *     the services it exposes, hunting the standard Battery
 *     Service (0x180F / 0x2A19);
 *  3. enumerates content providers of Galaxy Wearable, the
 *     Wear OS app and the settings-intelligence widget host,
 *     and tries querying exported battery/device authorities.
 * Once per process, log-only, tag prefix "WatchProbe".
 */
internal object WatchDataProbe {
    private const val TAG = "LiveUpdateListener"

    private val batteryServiceUuid: UUID =
        UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
    private val batteryLevelUuid: UUID =
        UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")

    private val providerPackages = listOf(
        "com.samsung.android.app.watchmanager",
        "com.google.android.wearable.app",
        "com.android.settings.intelligence"
    )

    private val wideNet = listOf(
        "batt", "device", "watch", "wear", "accessor",
        "companion", "galaxy", "samsung", "sem"
    )

    @Volatile
    private var done: Boolean = false

    fun runOnce(context: Context, devices: List<ConnectedDeviceInfo>) {
        if (done) return
        done = true
        val appContext = context.applicationContext
        Thread {
            runCatching { probeServices() }
                .onFailure { Log.i(TAG, "WatchProbe: services failed: $it") }
            runCatching { probeProviders(appContext) }
                .onFailure { Log.i(TAG, "WatchProbe: providers failed: $it") }
            val watch = devices.firstOrNull {
                it.kind == ConnectedDeviceKind.WATCH
            }
            if (watch == null) {
                Log.i(TAG, "WatchProbe: no watch in device list, GATT skipped")
            } else {
                runCatching { probeGatt(appContext, watch.address) }
                    .onFailure { Log.i(TAG, "WatchProbe: gatt failed: $it") }
            }
            Log.i(TAG, "WatchProbe: done")
        }.start()
    }

    private fun probeServices() {
        val serviceManager = Class.forName("android.os.ServiceManager")
        val listMethod = serviceManager.getMethod("listServices")
        @Suppress("UNCHECKED_CAST")
        val names = (listMethod.invoke(null) as? Array<String>)
            ?.toList().orEmpty()
        Log.i(TAG, "WatchProbe: ${names.size} services total")
        names.chunked(80).forEachIndexed { index, chunk ->
            Log.i(
                TAG,
                "WatchProbe: SvcChunk ${index + 1}: ${chunk.joinToString(",")}"
            )
        }
        val getService = serviceManager.getMethod(
            "getService",
            String::class.java
        )
        val candidates = names.filter { name ->
            val lower = name.lowercase()
            wideNet.any { lower.contains(it) }
        }
        Log.i(TAG, "WatchProbe: ${candidates.size} wide-net candidates")
        for (name in candidates) {
            val binder = runCatching {
                getService.invoke(null, name) as? IBinder
            }.getOrNull() ?: continue
            val descriptor = runCatching { binder.interfaceDescriptor }
                .getOrElse { error -> "descriptor failed: $error" }
            Log.i(TAG, "WatchProbe: svc $name -> $descriptor")
        }
    }

    private fun probeProviders(context: Context) {
        val pm = context.packageManager
        for (pkg in providerPackages) {
            val info = try {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(pkg, PackageManager.GET_PROVIDERS)
            } catch (error: Exception) {
                Log.i(TAG, "WatchProbe: pkg $pkg not visible: $error")
                continue
            }
            val providers = info.providers.orEmpty()
            Log.i(TAG, "WatchProbe: pkg $pkg providers=${providers.size}")
            for (provider in providers) {
                Log.i(
                    TAG,
                    "WatchProbe: provider ${provider.authority} " +
                        "exported=${provider.exported} " +
                        "readPerm=${provider.readPermission}"
                )
                val authority = provider.authority.orEmpty()
                if (
                    provider.exported &&
                    (authority.contains("batt") || authority.contains("device"))
                ) {
                    val cursor = runCatching {
                        context.contentResolver.query(
                            Uri.parse("content://$authority"),
                            null, null, null, null
                        )
                    }.getOrNull()
                    Log.i(
                        TAG,
                        "WatchProbe: query $authority -> " +
                            (cursor?.let { "count=${it.count}" } ?: "null/denied")
                    )
                    cursor?.close()
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun probeGatt(context: Context, address: String) {
        val manager = context.getSystemService(BluetoothManager::class.java)
        val adapter: BluetoothAdapter? = manager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            Log.i(TAG, "WatchProbe: adapter unavailable, GATT skipped")
            return
        }
        val device = try {
            adapter.getRemoteDevice(address)
        } catch (error: Exception) {
            Log.i(TAG, "WatchProbe: getRemoteDevice failed: $error")
            return
        }
        Log.i(TAG, "WatchProbe: GATT connecting to $address")
        @Volatile var finished = false
        val gattRef = arrayOfNulls<BluetoothGatt>(1)
        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(
                gatt: BluetoothGatt,
                status: Int,
                newState: Int
            ) {
                Log.i(
                    TAG,
                    "WatchProbe: GATT state status=$status newState=$newState"
                )
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    gatt.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    finished = true
                    gatt.close()
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                val services = gatt.services.map { it.uuid.toString() }
                Log.i(
                    TAG,
                    "WatchProbe: GATT services status=$status: $services"
                )
                val battery = gatt.getService(batteryServiceUuid)
                val level = battery?.getCharacteristic(batteryLevelUuid)
                if (level == null) {
                    Log.i(TAG, "WatchProbe: no Battery Service/level char")
                    finished = true
                    gatt.disconnect()
                    gatt.close()
                    return
                }
                Log.i(TAG, "WatchProbe: Battery Service found, reading level")
                @Suppress("DEPRECATION")
                gatt.readCharacteristic(level)
            }

            @Deprecated("Deprecated in Java")
            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                val value = characteristic.value
                Log.i(
                    TAG,
                    "WatchProbe: battery level read status=$status " +
                        "value=${value?.firstOrNull()}"
                )
                finished = true
                gatt.disconnect()
                gatt.close()
            }
        }
        val gatt = device.connectGatt(
            context,
            false,
            callback,
            BluetoothDevice.TRANSPORT_AUTO
        )
        gattRef[0] = gatt
        if (gatt == null) {
            Log.i(TAG, "WatchProbe: connectGatt returned null")
            return
        }
        // Watchdog: never leave a dangling GATT client behind.
        Thread {
            Thread.sleep(12_000)
            if (!finished) {
                Log.i(TAG, "WatchProbe: GATT timeout, closing")
                runCatching {
                    gatt.disconnect()
                    gatt.close()
                }
            }
        }.start()
    }
}
