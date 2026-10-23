package com.kakao.taxi.liveupdate

import android.bluetooth.BluetoothDevice
import java.lang.reflect.Method

/**
 * Tier 2 (round 85): reads the battery level the Bluetooth
 * stack itself tracks for a connected device — HFP battery
 * indicators and the BLE Battery Service both land in that one
 * value. It is exposed only through the hidden
 * BluetoothDevice.getBatteryLevel(), so the read is reflective
 * and defensive: any failure, and any value outside 0..100,
 * reads as "no battery known". Proven on the owner's phone
 * (rounds 83–84): the soundcore K20i earbuds report a real
 * level (80) while the BR/EDR-only Galaxy Watch FE reports
 * unknown — Samsung's widget gets the watch's number from
 * Samsung's own companion channel instead.
 */
internal object DeviceBatteryReader {
    @Volatile
    private var method: Method? = null

    @Volatile
    private var lookupDone: Boolean = false

    fun read(device: BluetoothDevice): Int? {
        val batteryMethod = resolveMethod(device) ?: return null
        val raw = runCatching { batteryMethod.invoke(device) as? Int }
            .getOrNull() ?: return null
        return raw.takeIf { it in 0..100 }
    }

    private fun resolveMethod(device: BluetoothDevice): Method? {
        if (!lookupDone) {
            synchronized(this) {
                if (!lookupDone) {
                    method = runCatching {
                        device.javaClass.getMethod("getBatteryLevel")
                    }.getOrNull()
                    lookupDone = true
                }
            }
        }
        return method
    }
}
