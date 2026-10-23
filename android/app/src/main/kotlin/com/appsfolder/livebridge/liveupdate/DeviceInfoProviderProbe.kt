package com.kakao.taxi.liveupdate

import android.content.Context
import android.net.Uri
import android.util.Log

/**
 * Round 90: round 89 found a genuinely open door — Galaxy
 * Wearable ships an exported, permission-less content
 * provider named deviceinfocontentprovider. Querying its
 * root returned null (unknown-URI providers answer that
 * way), so this probe walks a matrix of plausible child
 * paths, logs the exact outcome of each (exception class,
 * null cursor, or the cursor's columns + first rows), and
 * tries a few provider call() methods. Once per process,
 * log-only, tag prefix "ProviderProbe".
 */
internal object DeviceInfoProviderProbe {
    private const val TAG = "LiveUpdateListener"
    private const val AUTHORITY =
        "com.samsung.android.wearable.deviceinfocontentprovider.provider"

    private val paths = listOf(
        "", "deviceinfo", "device_info", "device", "devices",
        "deviceinfos", "device_list", "devicelist", "info",
        "battery", "batterylevel", "connected", "connected_devices",
        "connecteddevices", "paired", "paired_devices", "all",
        "list", "watch", "wearable", "current", "status",
        "devicestatus", "getDeviceInfo"
    )

    private val callMethods = listOf(
        "getDeviceInfo", "getDevices", "deviceinfo", "get_device_info"
    )

    @Volatile
    private var done: Boolean = false

    fun runOnce(context: Context) {
        if (done) return
        done = true
        val appContext = context.applicationContext
        Thread {
            val resolver = appContext.contentResolver
            for (path in paths) {
                val uri = Uri.parse("content://$AUTHORITY/$path")
                val outcome = runCatching {
                    val cursor = resolver.query(uri, null, null, null, null)
                    if (cursor == null) {
                        "NULL cursor"
                    } else {
                        cursor.use {
                            val columns = it.columnNames.joinToString(",")
                            val rows = mutableListOf<String>()
                            var index = 0
                            while (it.moveToNext() && index < 5) {
                                val values = (0 until it.columnCount).map { col ->
                                    runCatching {
                                        it.getString(col)?.take(60) ?: "null"
                                    }.getOrElse { error -> "<$error>" }
                                }
                                rows.add(values.joinToString(" | "))
                                index++
                            }
                            "CURSOR count=${it.count} cols=[$columns] " +
                                "rows=$rows"
                        }
                    }
                }.getOrElse { error ->
                    "EXC ${error.javaClass.simpleName}: " +
                        (error.message ?: "").take(120)
                }
                Log.i(TAG, "ProviderProbe: /$path -> $outcome")
            }
            for (method in callMethods) {
                val outcome = runCatching {
                    @Suppress("DEPRECATION")
                    val bundle = resolver.call(AUTHORITY, method, null, null)
                    if (bundle == null) {
                        "NULL bundle"
                    } else {
                        val keys = bundle.keySet().joinToString(",")
                        val values = bundle.keySet().take(12).joinToString("; ") { key ->
                            "$key=${bundle.get(key)}".take(80)
                        }
                        "BUNDLE keys=[$keys] $values"
                    }
                }.getOrElse { error ->
                    "EXC ${error.javaClass.simpleName}: " +
                        (error.message ?: "").take(120)
                }
                Log.i(TAG, "ProviderProbe: call $method -> $outcome")
            }
            Log.i(TAG, "ProviderProbe: done")
        }.start()
    }
}
