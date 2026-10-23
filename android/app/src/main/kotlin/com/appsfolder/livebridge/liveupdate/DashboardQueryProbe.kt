package com.kakao.taxi.liveupdate

import android.content.Context
import android.net.Uri
import android.util.Log

/**
 * Round 92: round 91 read the provider's own constants out
 * of Galaxy Wearable — the class is DashboardContent
 * Provider, its table is "dashboard_table", and it has a
 * dedicated updateColumnBatteryInfo method, so the table
 * holds per-device battery data. Round 90's matrix never
 * tried that path (the provider returns null for unmatched
 * URIs instead of throwing). This probe queries the real
 * URIs and dumps columns + rows verbatim. Once per
 * process, log-only, tag prefix "DashProbe".
 */
internal object DashboardQueryProbe {
    private const val TAG = "LiveUpdateListener"
    private const val AUTHORITY =
        "com.samsung.android.wearable.deviceinfocontentprovider.provider"

    private val uris = listOf(
        "content://$AUTHORITY/dashboard_table",
        "content://$AUTHORITY/dashboard_table/1"
    )

    @Volatile
    private var done: Boolean = false

    fun runOnce(context: Context) {
        if (done) return
        done = true
        val appContext = context.applicationContext
        Thread {
            // Round 93: what is this permission, and did the
            // manifest declaration get us granted?
            val dashboardPermission =
                "com.samsung.android.wearable.permission.DEVICE_DASHBOARD_PERMISSION"
            val pm = appContext.packageManager
            val permissionInfo = runCatching {
                @Suppress("DEPRECATION")
                pm.getPermissionInfo(dashboardPermission, 0)
            }.getOrNull()
            if (permissionInfo == null) {
                Log.i(TAG, "DashProbe: permission info not found")
            } else {
                val level = permissionInfo.protectionLevel
                val base = level and 0xf
                Log.i(
                    TAG,
                    "DashProbe: permission level raw=$level base=$base " +
                        "(0=normal,1=dangerous,2=signature) " +
                        "package=${permissionInfo.packageName}"
                )
            }
            val selfCheck = pm.checkPermission(
                dashboardPermission,
                appContext.packageName
            )
            Log.i(
                TAG,
                "DashProbe: self permission check=$selfCheck (0=granted)"
            )
            val resolver = appContext.contentResolver
            for (uriText in uris) {
                val uri = Uri.parse(uriText)
                val type = runCatching { resolver.getType(uri) }
                    .getOrElse { error -> "EXC ${error.javaClass.simpleName}" }
                Log.i(TAG, "DashProbe: $uriText type=$type")
                val outcome = runCatching {
                    val cursor = resolver.query(uri, null, null, null, null)
                    if (cursor == null) {
                        "NULL cursor"
                    } else {
                        cursor.use {
                            val columns = it.columnNames.joinToString(",")
                            val rows = mutableListOf<String>()
                            var index = 0
                            while (it.moveToNext() && index < 10) {
                                val values = (0 until it.columnCount).map { col ->
                                    val name = it.getColumnName(col)
                                    val value = runCatching {
                                        it.getString(col)?.take(120) ?: "null"
                                    }.getOrElse { error -> "<$error>" }
                                    "$name=$value"
                                }
                                rows.add(values.joinToString(" | "))
                                index++
                            }
                            "CURSOR count=${it.count} cols=[$columns]\n" +
                                rows.joinToString("\n")
                        }
                    }
                }.getOrElse { error ->
                    "EXC ${error.javaClass.simpleName}: " +
                        (error.message ?: "").take(160)
                }
                Log.i(TAG, "DashProbe: $uriText -> $outcome")
            }
            Log.i(TAG, "DashProbe: done")
        }.start()
    }
}
