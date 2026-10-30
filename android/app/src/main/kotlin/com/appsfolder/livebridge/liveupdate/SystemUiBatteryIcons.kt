package com.kakao.taxi.liveupdate

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.util.Log

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
internal object SystemUiBatteryIcons {

    private const val TAG = "LiveUpdateListener"
    private const val SOURCE_PACKAGE = "com.android.systemui"
    private const val RESOURCE_NAME = "stat_notify_battery_caution"
    private const val RENDER_SIZE_PX = 192

    @Volatile
    private var cached: Bitmap? = null

    @Volatile
    private var missing = false

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

    private fun loadBitmap(context: Context): Bitmap? {
        return try {
            val packageContext = context.createPackageContext(
                SOURCE_PACKAGE,
                Context.CONTEXT_IGNORE_SECURITY
            )
            val resources = packageContext.resources
            val resourceId = resources.getIdentifier(RESOURCE_NAME, "drawable", SOURCE_PACKAGE)
            if (resourceId == 0) {
                Log.i(TAG, "SystemUiBatteryIcons: $RESOURCE_NAME not found")
                return null
            }
            @Suppress("DEPRECATION")
            val drawable = resources.getDrawable(resourceId, null)
            if (drawable == null) {
                Log.i(TAG, "SystemUiBatteryIcons: $RESOURCE_NAME drawable is null")
                return null
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
            Log.i(TAG, "SystemUiBatteryIcons: $RESOURCE_NAME loaded")
            white
        } catch (error: Exception) {
            Log.i(TAG, "SystemUiBatteryIcons: failed for $RESOURCE_NAME: $error")
            null
        }
    }
}
