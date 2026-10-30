package com.appsfolder.livebridge.liveupdate

import android.content.Context
import android.util.Log

// Round 100 probe (owner request): the low-battery capsule
// should use Samsung SystemUI's own icons instead of the
// hand-drawn vector — the plain low-battery glyph at 5-15% and
// the battery-with-exclamation glyph below 5%. The exact
// drawable names inside SystemUI are unknown, so this probe
// reflects over SystemUI's R$drawable class (same
// package-context introspection technique as the round-91
// DashboardContentProvider probe), lists every drawable whose
// name contains "batter", and tries loading each one. The
// names + load results in the log decide which two drawables
// get wired into the capsule; the hand-drawn vector stays as
// the fallback when a SystemUI glyph cannot be loaded.
internal object SystemUiIconProbe {
    private const val TAG = "LiveUpdateListener"
    private const val SYSTEMUI_PACKAGE = "com.android.systemui"

    @Volatile
    private var done = false

    fun runOnce(context: Context) {
        if (done) {
            return
        }
        done = true
        try {
            val pkgContext = context.createPackageContext(
                SYSTEMUI_PACKAGE,
                Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY
            )
            val drawableClass = Class.forName(
                "com.android.systemui.R\$drawable",
                true,
                pkgContext.classLoader
            )
            val fields = drawableClass.fields
            Log.i(TAG, "SysUiIconProbe: R\$drawable fields=${fields.size}")
            var batteryCount = 0
            for (field in fields) {
                val name = field.name
                if (!name.contains("batter", ignoreCase = true)) {
                    continue
                }
                batteryCount += 1
                val id = runCatching { field.getInt(null) }.getOrNull()
                if (id == null) {
                    Log.i(TAG, "SysUiIconProbe: $name id=? (not static int)")
                    continue
                }
                val loaded = runCatching {
                    pkgContext.resources.getDrawable(id, null)?.let { drawable ->
                        "${drawable.intrinsicWidth}x${drawable.intrinsicHeight}"
                    }
                }.getOrNull()
                Log.i(TAG, "SysUiIconProbe: $name id=$id loaded=$loaded")
            }
            Log.i(TAG, "SysUiIconProbe: battery drawables=$batteryCount done")
        } catch (error: Exception) {
            Log.w(TAG, "SysUiIconProbe: R\$drawable introspection failed", error)
            probeByIdentifierFallback(context)
        }
    }

    // Fallback when the R class cannot be loaded: try a small
    // list of plausible drawable names through the resource
    // table instead of reflection.
    private fun probeByIdentifierFallback(context: Context) {
        val candidates = listOf(
            "ic_battery_low",
            "stat_sys_battery_low",
            "ic_battery_alert",
            "battery_low",
            "tw_stat_sys_battery_low",
            "stat_sys_battery_charge_low",
            "ic_battery_warning",
            "battery_alert"
        )
        try {
            val pkgContext = context.createPackageContext(
                SYSTEMUI_PACKAGE,
                Context.CONTEXT_IGNORE_SECURITY
            )
            for (name in candidates) {
                val id = pkgContext.resources.getIdentifier(name, "drawable", SYSTEMUI_PACKAGE)
                val loaded = if (id != 0) {
                    runCatching {
                        pkgContext.resources.getDrawable(id, null)?.let { drawable ->
                            "${drawable.intrinsicWidth}x${drawable.intrinsicHeight}"
                        }
                    }.getOrNull()
                } else {
                    null
                }
                Log.i(TAG, "SysUiIconProbe: fallback $name id=$id loaded=$loaded")
            }
        } catch (error: Exception) {
            Log.w(TAG, "SysUiIconProbe: identifier fallback failed", error)
        }
    }
}
