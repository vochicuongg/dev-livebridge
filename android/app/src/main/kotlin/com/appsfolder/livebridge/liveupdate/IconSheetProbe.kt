package com.kakao.taxi.liveupdate

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/**
 * Visual companion to the round-76 probe (round 78): the device
 * glyphs Samsung's settings screens use are thin OUTLINE glyphs,
 * while the ic_* names wired in round 77 turned out to be the
 * filled variants — and the keyword filter may have missed the
 * outline set entirely. Instead of guessing names, this probe
 * renders every plausible candidate drawable from the Samsung
 * packages to a PNG in the app's external files directory; the
 * sheets are pulled over adb and eyeballed, and the next round
 * wires the exact resource names that match Samsung's screens.
 * Runs once per process; failures only log.
 */
internal object IconSheetProbe {

    private const val TAG = "LiveUpdateListener"
    private const val RENDER_SIZE_PX = 144
    private const val MAX_PER_PACKAGE = 220

    private val CANDIDATE_PACKAGES = listOf(
        "com.samsung.android.app.watchmanager",
        "com.samsung.android.lool",
        "com.samsung.android.app.aodservice",
        "com.samsung.android.waterplugin"
    )

    private val KEYWORDS = listOf(
        "watch", "bud", "ring", "fit", "band", "glass",
        "phone", "wearable", "device", "categor"
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
            runCatching { render(appContext) }
                .onFailure { error -> Log.w(TAG, "IconSheet failed", error) }
        }.start()
    }

    private fun render(context: Context) {
        val outDir = context.getExternalFilesDir("iconprobe")
        if (outDir == null) {
            Log.i(TAG, "IconSheet: no external files dir")
            return
        }
        outDir.mkdirs()
        var total = 0
        for (packageName in CANDIDATE_PACKAGES) {
            total += renderPackage(context, outDir, packageName)
        }
        Log.i(TAG, "IconSheet: done total=$total dir=${outDir.absolutePath}")
    }

    private fun renderPackage(context: Context, outDir: File, packageName: String): Int {
        val packageContext = runCatching {
            context.createPackageContext(
                packageName,
                Context.CONTEXT_IGNORE_SECURITY or Context.CONTEXT_INCLUDE_CODE
            )
        }.getOrNull()
        if (packageContext == null) {
            Log.i(TAG, "IconSheet: $packageName not installed")
            return 0
        }
        val resources = packageContext.resources
        // The R class belongs to the other package: it can only be
        // loaded through that package's own class loader. Loading
        // it with this app's class loader (round 78's first cut)
        // throws ClassNotFoundException for every package and the
        // enumeration silently came back empty.
        val names = runCatching {
            val drawableClass = packageContext.classLoader.loadClass("$packageName.R\$drawable")
            drawableClass.fields.map { field -> field.name }
        }.getOrDefault(emptyList())
        Log.i(TAG, "IconSheet: $packageName fields=${names.size}")
        val candidates = names.filter { name ->
            val lower = name.lowercase(Locale.ROOT)
            KEYWORDS.any { keyword -> lower.contains(keyword) }
        }.take(MAX_PER_PACKAGE)
        val shortPackage = packageName.substringAfterLast('.')
        var saved = 0
        for (name in candidates) {
            val savedFile = runCatching {
                val resourceId = resources.getIdentifier(name, "drawable", packageName)
                if (resourceId == 0) {
                    return@runCatching null
                }
                @Suppress("DEPRECATION")
                val drawable = resources.getDrawable(resourceId, null) ?: return@runCatching null
                val bitmap = Bitmap.createBitmap(
                    RENDER_SIZE_PX,
                    RENDER_SIZE_PX,
                    Bitmap.Config.ARGB_8888
                )
                val canvas = Canvas(bitmap)
                drawable.setBounds(0, 0, RENDER_SIZE_PX, RENDER_SIZE_PX)
                drawable.draw(canvas)
                val file = File(outDir, "${shortPackage}__${name}.png")
                FileOutputStream(file).use { stream ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                }
                file
            }.getOrNull()
            if (savedFile != null) {
                saved++
                Log.i(TAG, "IconSheet: saved ${savedFile.name}")
            }
        }
        Log.i(TAG, "IconSheet: $packageName candidates=${candidates.size} saved=$saved")
        return saved
    }
}
