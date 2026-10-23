package com.kakao.taxi.liveupdate

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log

/**
 * Round 95 (last unexplored branch): Wear OS-era Galaxy
 * watches are usually managed by a separate per-family
 * plugin package next to Galaxy Wearable itself, and the
 * watch's data flows through that plugin first. Rounds
 * 89-93 only examined watchmanager. This probe enumerates
 * every installed Samsung/watch-related package, lists its
 * content providers with their export/permission state,
 * and opportunistically queries any provider that is
 * exported with no read permission (other than the ones
 * already proven gated). Once per process, log-only, tag
 * prefix "PluginProbe".
 */
internal object PluginScanProbe {
    private const val TAG = "LiveUpdateListener"

    private val packageNeedles = listOf(
        "watch", "wear", "gear", "galaxy", "plugin"
    )

    private val alreadyKnownAuthorities = setOf(
        "com.samsung.android.wearable.deviceinfocontentprovider.provider"
    )

    @Volatile
    private var done: Boolean = false

    fun runOnce(context: Context) {
        if (done) return
        done = true
        val appContext = context.applicationContext
        Thread {
            val pm = appContext.packageManager
            @Suppress("DEPRECATION")
            val packages = pm.getInstalledPackages(0)
            val candidates = packages.filter { info ->
                val name = info.packageName.lowercase()
                name != appContext.packageName &&
                    (name.contains("samsung") || name.contains("sec.")) &&
                    packageNeedles.any { name.contains(it) }
            }
            Log.i(
                TAG,
                "PluginProbe: ${candidates.size} candidate packages"
            )
            for (info in candidates) {
                val pkg = info.packageName
                val isSystem = (info.applicationInfo?.flags ?: 0) and
                    ApplicationInfo.FLAG_SYSTEM != 0
                Log.i(TAG, "PluginProbe: pkg $pkg system=$isSystem")
                val withProviders = try {
                    @Suppress("DEPRECATION")
                    pm.getPackageInfo(pkg, PackageManager.GET_PROVIDERS)
                } catch (error: Exception) {
                    Log.i(TAG, "PluginProbe: pkg $pkg info failed: $error")
                    continue
                }
                for (provider in withProviders.providers.orEmpty()) {
                    val authority = provider.authority.orEmpty()
                    Log.i(
                        TAG,
                        "PluginProbe: provider $authority " +
                            "class=${provider.name} " +
                            "exported=${provider.exported} " +
                            "readPerm=${provider.readPermission}"
                    )
                    if (
                        provider.exported &&
                        provider.readPermission == null &&
                        authority.isNotEmpty() &&
                        authority !in alreadyKnownAuthorities
                    ) {
                        val outcome = runCatching {
                            val cursor = appContext.contentResolver.query(
                                Uri.parse("content://$authority"),
                                null, null, null, null
                            )
                            if (cursor == null) {
                                "NULL cursor"
                            } else {
                                cursor.use {
                                    "CURSOR count=${it.count} " +
                                        "cols=[${it.columnNames.joinToString(",")}]"
                                }
                            }
                        }.getOrElse { error ->
                            "EXC ${error.javaClass.simpleName}: " +
                                (error.message ?: "").take(100)
                        }
                        Log.i(TAG, "PluginProbe: query $authority -> $outcome")
                    }
                }
            }
            Log.i(TAG, "PluginProbe: done")
        }.start()
    }
}
