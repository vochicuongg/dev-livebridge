package com.kakao.taxi.liveupdate

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.util.Log

/**
 * Round 83 (tier-2 discovery): the system Bluetooth stack tracks
 * every connected device's battery itself — HFP battery
 * indicators for classic headsets/watches, the BLE Battery
 * Service for LE devices — and Samsung's own battery widget
 * shows those values (the owner's Watch FE read 59% there while
 * connected over BR/EDR as a handsfree device, which no public
 * API can see). The stack value is exposed only through the
 * hidden BluetoothDevice.getBatteryLevel(), so this probe asks
 * once per process, reflectively, for every device the
 * connected-devices capsule currently shows, and logs the raw
 * result (0..100, or a negative value / failure when the stack
 * has nothing or the hidden API is blocked). Diagnostic only;
 * whichever source wins gets wired into the capsule for real.
 */
internal object DeviceBatteryProbe {
    private const val PROBE_TAG = "LiveUpdateListener"

    @Volatile
    private var done: Boolean = false

    fun runOnce(context: Context, devices: List<ConnectedDeviceInfo>) {
        if (done || devices.isEmpty()) return
        done = true
        Thread {
            val adapter = try {
                BluetoothAdapter.getDefaultAdapter()
            } catch (error: Exception) {
                Log.i(PROBE_TAG, "BatteryProbe: no adapter: $error")
                return@Thread
            } ?: run {
                Log.i(PROBE_TAG, "BatteryProbe: adapter is null")
                return@Thread
            }
            for (info in devices) {
                val device = try {
                    adapter.getRemoteDevice(info.address)
                } catch (error: Exception) {
                    Log.i(PROBE_TAG, "BatteryProbe: ${info.name} no remote device: $error")
                    continue
                }
                val level: Any? = try {
                    val method = device.javaClass.getMethod("getBatteryLevel")
                    method.invoke(device)
                } catch (error: Exception) {
                    Log.i(
                        PROBE_TAG,
                        "BatteryProbe: ${info.name} (${info.kind}) " +
                            "getBatteryLevel failed: $error"
                    )
                    continue
                }
                Log.i(
                    PROBE_TAG,
                    "BatteryProbe: ${info.name} (${info.kind}) level=$level"
                )
            }
            Log.i(PROBE_TAG, "BatteryProbe: done")
        }.start()
    }
}
