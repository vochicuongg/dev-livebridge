package com.kakao.taxi.liveupdate

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import java.lang.reflect.Modifier

/**
 * Round 96: round 95 found the real thing in the watch
 * companion plugin (com.samsung.android.waterplugin): an
 * exported BatteryInfoProvider under watchstatus.device
 * care.battery, gated by the permission com.samsung.
 * android.companionservice.BATTERY_INFO_PROVIDER (plus a
 * SmartThings-Find battery helper behind READ_BATTERY).
 * This probe (A) logs both permissions' protection levels
 * and our grant state after the manifest declaration,
 * (B) dumps BatteryInfoProvider's static constants via the
 * plugin's package context to learn its exact URI
 * contract, and (C) queries the authority root plus a
 * path matrix harvested from those constants. Once per
 * process, log-only, tag prefix "BattInfoProbe".
 */
internal object BatteryInfoProbe {
    private const val TAG = "LiveUpdateListener"
    private const val PLUGIN_PACKAGE = "com.samsung.android.waterplugin"
    private const val AUTHORITY =
        "com.samsung.android.waterplugin.BatteryInfoProvider"
    private const val PROVIDER_CLASS =
        "com.samsung.android.companionservice.watchstatus." +
            "devicecare.battery.BatteryInfoProvider"

    private val permissions = listOf(
        "com.samsung.android.companionservice.BATTERY_INFO_PROVIDER",
        "com.samsung.android.companionapps.stfinder.READ_BATTERY"
    )

    @Volatile
    private var done: Boolean = false

    fun runOnce(context: Context) {
        if (done) return
        done = true
        val appContext = context.applicationContext
        Thread {
            logPermissions(appContext)
            val harvested = dumpProviderConstants(appContext)
            queryMatrix(appContext, harvested)
            Log.i(TAG, "BattInfoProbe: done")
        }.start()
    }

    private fun logPermissions(context: Context) {
        val pm = context.packageManager
        for (permission in permissions) {
            val info = runCatching {
                @Suppress("DEPRECATION")
                pm.getPermissionInfo(permission, 0)
            }.getOrNull()
            if (info == null) {
                Log.i(TAG, "BattInfoProbe: $permission not found")
                continue
            }
            val base = info.protectionLevel and 0xf
            val selfCheck = pm.checkPermission(permission, context.packageName)
            Log.i(
                TAG,
                "BattInfoProbe: $permission level base=$base " +
                    "(0=normal,1=dangerous,2=signature) " +
                    "definedBy=${info.packageName} selfCheck=$selfCheck (0=granted)"
            )
        }
    }

    private fun dumpProviderConstants(context: Context): List<String> {
        val harvested = mutableListOf<String>()
        val loader = try {
            @Suppress("DEPRECATION")
            context.createPackageContext(
                PLUGIN_PACKAGE,
                Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY
            ).classLoader
        } catch (error: Exception) {
            Log.i(TAG, "BattInfoProbe: package context failed: $error")
            return harvested
        }
        val clazz = try {
            Class.forName(PROVIDER_CLASS, false, loader)
        } catch (error: Exception) {
            Log.i(TAG, "BattInfoProbe: loadClass failed: $error")
            return harvested
        }
        Log.i(TAG, "BattInfoProbe: loaded ${clazz.name}")
        for (field in clazz.declaredFields) {
            if (!Modifier.isStatic(field.modifiers)) continue
            val value = runCatching {
                field.isAccessible = true
                field.get(null)?.toString()
            }.getOrNull()
            Log.i(
                TAG,
                "BattInfoProbe: field ${field.name}: " +
                    "${field.type.simpleName} = ${value?.take(160)}"
            )
            // Path/table candidates: short strings without the
            // package-name dot salad of authorities/permissions.
            if (
                value != null && value.length in 2..40 &&
                !value.contains('.') && !value.contains(' ')
            ) {
                harvested.add(value)
            }
            if (value != null && (value.startsWith("content://"))) {
                harvested.add(value)
            }
        }
        return harvested
    }

    private fun queryMatrix(context: Context, harvested: List<String>) {
        val resolver = context.contentResolver
        val paths = linkedSetOf(
            "",
            "battery", "batteryinfo", "battery_info", "info",
            "devicecare", "watchstatus", "status", "devices"
        )
        paths.addAll(harvested)
        for (path in paths) {
            val uri = if (path.startsWith("content://")) {
                Uri.parse(path)
            } else {
                Uri.parse("content://$AUTHORITY/$path")
            }
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
                                val name = it.getColumnName(col)
                                val value = runCatching {
                                    it.getString(col)?.take(100) ?: "null"
                                }.getOrElse { error -> "<$error>" }
                                "$name=$value"
                            }
                            rows.add(values.joinToString(" | "))
                            index++
                        }
                        "CURSOR count=${it.count} cols=[$columns] rows=$rows"
                    }
                }
            }.getOrElse { error ->
                "EXC ${error.javaClass.simpleName}: " +
                    (error.message ?: "").take(140)
            }
            Log.i(TAG, "BattInfoProbe: query $uri -> $outcome")
        }
    }
}
