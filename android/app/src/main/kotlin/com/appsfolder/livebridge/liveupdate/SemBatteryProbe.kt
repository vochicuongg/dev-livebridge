package com.kakao.taxi.liveupdate

import android.content.Context
import android.os.IBinder
import android.util.Log

/**
 * Round 86 (tier-2 discovery): Samsung's battery widget reads
 * the Watch FE's battery (58% while the Bluetooth stack says
 * "unknown") from a Samsung system service — the service's own
 * log line is "DeviceBatteryInfoService: semGetBatteryInfos()"
 * in system_server, and its client is Samsung's
 * BatteryProviderOneUi5. Whether a third-party app can reach
 * that service at all is unknown; this probe maps the doors:
 * it lists the binder services whose names mention battery or
 * Samsung, fetches the best candidates' interface descriptors,
 * and checks a handful of candidate Samsung framework classes
 * for battery entry points. Once per process, log-only.
 */
internal object SemBatteryProbe {
    private const val PROBE_TAG = "LiveUpdateListener"

    @Volatile
    private var done: Boolean = false

    private val candidateClasses = listOf(
        "com.samsung.android.bluetooth.SemBluetoothDevice",
        "com.samsung.android.devicebatteryinfo.SemDeviceBatteryInfoManager",
        "com.samsung.android.devicebatteryinfo.SemDeviceBatteryInfo",
        "android.os.SemDeviceBatteryInfo",
        "com.samsung.android.os.SemDeviceBatteryInfo",
        "com.samsung.android.bluetooth.SemBluetoothAdapter"
    )

    fun runOnce(context: Context) {
        if (done) return
        done = true
        Thread {
            try {
                probeServices()
            } catch (error: Exception) {
                Log.i(PROBE_TAG, "SemProbe: services failed: $error")
            }
            try {
                probeClasses()
            } catch (error: Exception) {
                Log.i(PROBE_TAG, "SemProbe: classes failed: $error")
            }
            Log.i(PROBE_TAG, "SemProbe: done")
        }.start()
    }

    private fun probeServices() {
        val serviceManager = Class.forName("android.os.ServiceManager")
        val listMethod = serviceManager.getMethod("listServices")
        @Suppress("UNCHECKED_CAST")
        val names = (listMethod.invoke(null) as? Array<String>)?.toList().orEmpty()
        Log.i(PROBE_TAG, "SemProbe: ${names.size} services total")
        val interesting = names.filter { name ->
            val lower = name.lowercase()
            lower.contains("batt") || lower.contains("sem") ||
                lower.contains("deviceinfo")
        }
        for (name in interesting) {
            Log.i(PROBE_TAG, "SemProbe: service candidate: $name")
        }
        val getService = serviceManager.getMethod(
            "getService",
            String::class.java
        )
        for (name in interesting) {
            if (!name.lowercase().contains("batt")) continue
            val binder = runCatching {
                getService.invoke(null, name) as? IBinder
            }.getOrNull()
            if (binder == null) {
                Log.i(PROBE_TAG, "SemProbe: getService($name) -> null")
                continue
            }
            val descriptor = runCatching { binder.interfaceDescriptor }
                .getOrElse { error -> "descriptor failed: $error" }
            Log.i(
                PROBE_TAG,
                "SemProbe: getService($name) -> binder, descriptor=$descriptor"
            )
        }
    }

    private fun probeClasses() {
        for (className in candidateClasses) {
            val clazz = try {
                Class.forName(className)
            } catch (error: Exception) {
                Log.i(PROBE_TAG, "SemProbe: class $className not found")
                continue
            }
            val batteryMethods = clazz.declaredMethods
                .map { it.name }
                .filter { it.lowercase().contains("batter") }
                .distinct()
            Log.i(
                PROBE_TAG,
                "SemProbe: class $className FOUND, " +
                    "battery methods: $batteryMethods"
            )
        }
    }
}
