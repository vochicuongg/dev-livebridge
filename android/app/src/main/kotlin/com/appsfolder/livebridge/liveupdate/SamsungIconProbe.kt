package com.kakao.taxi.liveupdate

import android.content.Context
import android.util.Log
import java.util.Locale

/**
 * Discovery probe for the runtime Samsung-icon loading (round
 * 76, owner's choice): the device glyphs shown by Samsung's own
 * battery widget live inside Samsung system apps — chiefly
 * Device Care (com.samsung.android.lool). Nothing about their
 * resource names is public, so before any icon is wired into
 * the capsule this probe enumerates the drawable tables of the
 * candidate packages on the device itself and logs every name
 * that looks device-related. Runs once per process, on a
 * background thread, and changes no behavior.
 */
internal object SamsungIconProbe {

    // Same tag as the listener so one logcat filter catches both.
    private const val TAG = "LiveUpdateListener"

    private val CANDIDATE_PACKAGES = listOf(
        "com.samsung.android.lool",
        "com.samsung.android.app.devicecare",
        "com.samsung.android.app.watchmanager",
        "com.samsung.android.waterplugin",
        "com.samsung.android.app.aodservice"
    )

    private val DEVICE_KEYWORDS = listOf(
        "watch", "bud", "ring", "glass", "fit", "band",
        "phone", "wearable", "device", "earphone", "headset"
    )

    @Volatile
    private var done = false

    fun runOnce(context: Context) {
        if (done) {
            return
        }
        done = true
        val appContext = context.applicationContext
        Thread {
            runCatching { probe(appContext) }
                .onFailure { error -> Log.w(TAG, "IconProbe failed", error) }
        }.start()
    }

    private fun probe(context: Context) {
        val packageManager = context.packageManager
        for (pkg in CANDIDATE_PACKAGES) {
            val installed = runCatching {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(pkg, 0)
            }.isSuccess
            if (!installed) {
                Log.i(TAG, "IconProbe: $pkg not installed")
                continue
            }
            val packageContext = runCatching {
                context.createPackageContext(
                    pkg,
                    Context.CONTEXT_IGNORE_SECURITY or Context.CONTEXT_INCLUDE_CODE
                )
            }.getOrNull()
            if (packageContext == null) {
                Log.i(TAG, "IconProbe: $pkg package context unavailable")
                continue
            }
            val drawableClass = runCatching {
                packageContext.classLoader.loadClass("$pkg.R\$drawable")
            }.getOrNull()
            if (drawableClass == null) {
                Log.i(TAG, "IconProbe: $pkg R.drawable class not found")
                continue
            }
            val fields = runCatching { drawableClass.fields }.getOrDefault(emptyArray())
            Log.i(TAG, "IconProbe: $pkg drawable entries=${fields.size}")
            val hits = fields.filter { field ->
                val name = field.name.lowercase(Locale.ROOT)
                DEVICE_KEYWORDS.any { keyword -> name.contains(keyword) }
            }
            Log.i(TAG, "IconProbe: $pkg device-related=${hits.size}")
            for (field in hits.take(150)) {
                Log.i(TAG, "IconProbe: $pkg :: ${field.name}")
            }
            if (hits.size > 150) {
                Log.i(TAG, "IconProbe: $pkg :: ... (${hits.size - 150} more)")
            }
        }
        Log.i(TAG, "IconProbe: done")
    }
}
