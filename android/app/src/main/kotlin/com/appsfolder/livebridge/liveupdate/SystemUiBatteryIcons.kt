package com.kakao.taxi.liveupdate

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

// Round 105: the low-battery capsule uses Samsung SystemUI's own
// caution glyph — stat_notify_battery_caution, the asset the
// owner extracted from SystemUI.apk (a 96x96 mask: opaque
// battery, transparent "!" cutout). Loaded at runtime from
// com.android.systemui, the same route as the Wearable glyphs
// (rounds 77-82): nothing Samsung-owned is copied into this
// app. The asset's pixels are black, so the render is
// recolored white through its own alpha (DST_IN) — that is how
// Samsung's badge shows a white battery with the badge colour
// in the "!" cutout, and it makes the glyph read white on
// LiveBridge's red pill too. Any failure returns null and the
// caller falls back to the redrawn ic_battery_alert vector.
//
// Round 106: the vector code file the owner confirmed,
// ic_accessibility_hearing_aid_low_battery (a 19x19 vector:
// vertical battery with the "!" cut out by opposite winding),
// was tried FIRST — a vector renders crisply at any size, which
// is the display optimization the owner asked for — with the
// caution webp kept as the second source.
//
// Round 107: reverted to webp-first. Device evidence showed
// the vector renders SMALLER than the round-105 webp: the
// vector's glyph fills only ~43% of its viewport width (thick
// transparent side margins) versus ~58% for the webp, so on the
// pill the vector looked small. The vector stays as the
// fallback when the webp is absent.
//
// Round 108: clear the colour of fully transparent pixels
// after the DST_IN recolor. DST_IN leaves white RGB under
// alpha 0; renderers that draw the bitmap ignoring alpha
// (some devices' Now Bar pill) then show a blank white square
// instead of the glyph — exactly the owner's screenshot.
// Zeroing those pixels makes the glyph visible even there
// (black square + white glyph), while correct alpha
// compositing (Flip5) is unchanged. Applies to both the webp
// and the vector source, which share this recolor step.
internal object SystemUiBatteryIcons {

    private const val TAG = "LiveUpdateListener"
    private const val SOURCE_PACKAGE = "com.android.systemui"
    private const val VECTOR_RESOURCE_NAME = "ic_accessibility_hearing_aid_low_battery"
    private const val BITMAP_RESOURCE_NAME = "stat_notify_battery_caution"
    private const val RENDER_SIZE_PX = 192
    private const val BADGE_CORNER_RADIUS_PX = 48f

    @Volatile
    private var cached: Bitmap? = null

    @Volatile
    private var missing = false

    private val badgeCache = ConcurrentHashMap<Int, Bitmap>()

    fun iconBitmap(context: Context): Bitmap? {
        cached?.let { return it }
        if (missing) {
            return null
        }
        val bitmap = loadBitmap(context.applicationContext)
        if (bitmap == null) {
            missing = true
            return null
        }
        cached = bitmap
        return bitmap
    }

    // Round 109: the same glyph on its own badge background —
    // a rounded rect in the capsule's colour. Some devices
    // draw the tapped (expanded) pill's icon on a LIGHT badge,
    // where a bare white glyph vanishes (the owner's J7
    // screenshot: white square when tapped, normal pill when
    // collapsed). A self-contained badge reads on any
    // background: on the red pill it blends into the pill
    // colour, on a light badge it shows as a red badge with a
    // white glyph. The caller uses this for every icon slot
    // (small, large, chip, nowBar) so the expanded view is
    // fixed whichever slot its SystemUI draws.
    fun badgeBitmap(context: Context, color: Int): Bitmap? {
        badgeCache[color]?.let { return it }
        val glyph = iconBitmap(context) ?: return null
        val badge = Bitmap.createBitmap(
            RENDER_SIZE_PX,
            RENDER_SIZE_PX,
            Bitmap.Config.ARGB_8888
        )
        Canvas(badge).also { canvas ->
            val paint = Paint().apply {
                this.color = color
                isAntiAlias = true
            }
            val rect = RectF(
                0f, 0f,
                RENDER_SIZE_PX.toFloat(), RENDER_SIZE_PX.toFloat()
            )
            canvas.drawRoundRect(rect, BADGE_CORNER_RADIUS_PX, BADGE_CORNER_RADIUS_PX, paint)
            canvas.drawBitmap(glyph, 0f, 0f, null)
        }
        badgeCache[color] = badge
        return badge
    }

    private fun loadBitmap(context: Context): Bitmap? {
        return try {
            val packageContext = context.createPackageContext(
                SOURCE_PACKAGE,
                Context.CONTEXT_IGNORE_SECURITY
            )
            val resources = packageContext.resources
            for (resourceName in listOf(BITMAP_RESOURCE_NAME, VECTOR_RESOURCE_NAME)) {
                val resourceId = resources.getIdentifier(resourceName, "drawable", SOURCE_PACKAGE)
                if (resourceId == 0) {
                    Log.i(TAG, "SystemUiBatteryIcons: $resourceName not found")
                    continue
                }
                @Suppress("DEPRECATION")
                val drawable = resources.getDrawable(resourceId, null)
                if (drawable == null) {
                    Log.i(TAG, "SystemUiBatteryIcons: $resourceName drawable is null")
                    continue
                }
                val rendered = Bitmap.createBitmap(
                    RENDER_SIZE_PX,
                    RENDER_SIZE_PX,
                    Bitmap.Config.ARGB_8888
                )
                Canvas(rendered).also { canvas ->
                    drawable.setBounds(0, 0, RENDER_SIZE_PX, RENDER_SIZE_PX)
                    drawable.draw(canvas)
                }
                // Recolor through the asset's own alpha: white where
                // the battery is opaque, transparent where the "!"
                // is cut out.
                val white = Bitmap.createBitmap(
                    RENDER_SIZE_PX,
                    RENDER_SIZE_PX,
                    Bitmap.Config.ARGB_8888
                )
                Canvas(white).also { canvas ->
                    canvas.drawColor(Color.WHITE)
                    val paint = Paint().apply {
                        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
                    }
                    canvas.drawBitmap(rendered, 0f, 0f, paint)
                }
                rendered.recycle()
                // Round 108: fully transparent pixels must carry
                // no colour, or alpha-ignoring renderers show a
                // blank white square (see the comment above).
                val pixels = IntArray(RENDER_SIZE_PX * RENDER_SIZE_PX)
                white.getPixels(
                    pixels, 0, RENDER_SIZE_PX, 0, 0,
                    RENDER_SIZE_PX, RENDER_SIZE_PX
                )
                for (i in pixels.indices) {
                    if (pixels[i] ushr 24 == 0) {
                        pixels[i] = 0
                    }
                }
                white.setPixels(
                    pixels, 0, RENDER_SIZE_PX, 0, 0,
                    RENDER_SIZE_PX, RENDER_SIZE_PX
                )
                Log.i(TAG, "SystemUiBatteryIcons: $resourceName loaded")
                return white
            }
            null
        } catch (error: Exception) {
            Log.i(TAG, "SystemUiBatteryIcons: failed: $error")
            null
        }
    }
}
