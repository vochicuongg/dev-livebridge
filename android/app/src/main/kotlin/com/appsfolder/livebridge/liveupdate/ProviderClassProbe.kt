package com.kakao.taxi.liveupdate

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import java.lang.reflect.Modifier

/**
 * Round 91: round 90's path matrix returned NULL for every
 * URI (even invented ones), so Wearable's device-info
 * provider is either silently caller-gated or its query()
 * is a stub and the contract lives elsewhere. Blind
 * guessing stops here: this probe loads the provider's own
 * class through Galaxy Wearable's package context (the same
 * technique SamsungDeviceIcons uses for its glyphs) and
 * dumps the class's static constants — providers normally
 * declare their authority, URI paths and call() method
 * names as static fields — plus its declared methods and
 * the same for its superclass. Once per process, log-only,
 * tag prefix "ClassProbe".
 */
internal object ProviderClassProbe {
    private const val TAG = "LiveUpdateListener"
    private const val PACKAGE_NAME = "com.samsung.android.app.watchmanager"
    private const val AUTHORITY_MARKER = "deviceinfocontentprovider"

    @Volatile
    private var done: Boolean = false

    fun runOnce(context: Context) {
        if (done) return
        done = true
        val appContext = context.applicationContext
        Thread {
            val className = findProviderClassName(appContext)
            if (className == null) {
                Log.i(TAG, "ClassProbe: provider class not found")
                return@Thread
            }
            val loader = try {
                @Suppress("DEPRECATION")
                appContext.createPackageContext(
                    PACKAGE_NAME,
                    Context.CONTEXT_INCLUDE_CODE or
                        Context.CONTEXT_IGNORE_SECURITY
                ).classLoader
            } catch (error: Exception) {
                Log.i(TAG, "ClassProbe: package context failed: $error")
                return@Thread
            }
            val clazz = try {
                Class.forName(className, false, loader)
            } catch (error: Exception) {
                Log.i(TAG, "ClassProbe: loadClass failed: $error")
                return@Thread
            }
            Log.i(TAG, "ClassProbe: loaded ${clazz.name}")
            dumpClass(clazz, "class")
            clazz.superclass?.let { dumpClass(it, "super") }
            Log.i(TAG, "ClassProbe: done")
        }.start()
    }

    private fun findProviderClassName(context: Context): String? {
        val pm = context.packageManager
        val info = try {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(PACKAGE_NAME, PackageManager.GET_PROVIDERS)
        } catch (error: Exception) {
            Log.i(TAG, "ClassProbe: getPackageInfo failed: $error")
            return null
        }
        for (provider in info.providers.orEmpty()) {
            val authority = provider.authority.orEmpty()
            if (authority.contains(AUTHORITY_MARKER)) {
                Log.i(
                    TAG,
                    "ClassProbe: provider class=${provider.name} " +
                        "authority=$authority"
                )
                return provider.name
            }
        }
        return null
    }

    private fun dumpClass(clazz: Class<*>, label: String) {
        for (field in clazz.declaredFields) {
            val isStatic = Modifier.isStatic(field.modifiers)
            val valueText = if (isStatic) {
                runCatching {
                    field.isAccessible = true
                    field.get(null)?.toString()?.take(160) ?: "null"
                }.getOrElse { error -> "<get failed: $error>" }
            } else {
                ""
            }
            Log.i(
                TAG,
                "ClassProbe: $label field ${field.name}: " +
                    "${field.type.simpleName} static=$isStatic $valueText"
            )
        }
        for (method in clazz.declaredMethods) {
            val params = method.parameterTypes
                .joinToString(",") { it.simpleName }
            Log.i(
                TAG,
                "ClassProbe: $label method ${method.name}($params) " +
                    "static=${Modifier.isStatic(method.modifiers)}"
            )
        }
    }
}
