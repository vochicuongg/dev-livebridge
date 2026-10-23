package com.kakao.taxi.liveupdate

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import java.util.concurrent.ConcurrentHashMap

/**
 * Runtime loading of Samsung's own device glyphs (round 77,
 * owner's choice): the connected-devices capsule borrows its
 * icons from the Galaxy Wearable app installed on the phone
 * (com.samsung.android.app.watchmanager), whose drawable table
 * the round-76 probe enumerated on the owner's device — ic_watch,
 * ic_buds, ic_fit, ic_ring and friends are the exact glyphs
 * Samsung's own device screens use. Nothing is copied into this
 * app: the glyph is loaded from Samsung's package at runtime,
 * rendered, and reduced to a white silhouette of its alpha shape
 * (the form notification icons take). Any failure — package
 * absent, resource renamed, render error — returns null and the
 * capsule falls back to its built-in per-kind icons. Kinds with
 * no Samsung glyph (glasses, headphones, speaker) always use
 * the built-in icons.
 */
internal object SamsungDeviceIcons {

    private const val SOURCE_PACKAGE = "com.samsung.android.app.watchmanager"
    private const val RENDER_SIZE_PX = 96

    private val RESOURCE_NAMES = mapOf(
        ConnectedDeviceKind.WATCH to "ic_watch",
        ConnectedDeviceKind.BUDS to "ic_buds",
        ConnectedDeviceKind.BAND to "ic_fit",
        ConnectedDeviceKind.RING to "ic_ring",
        ConnectedDeviceKind.PHONE to "ic_perm_group_phone",
        ConnectedDeviceKind.GENERIC to "ic_galaxy_wearable_for_connected_devices"
    )

    private val bitmapCache = ConcurrentHashMap<ConnectedDeviceKind, Bitmap>()
    private val missingKinds = ConcurrentHashMap.newKeySet<ConnectedDeviceKind>()

    fun iconBitmap(context: Context, kind: ConnectedDeviceKind): Bitmap? {
        bitmapCache[kind]?.let { return it }
        if (kind in missingKinds) {
            return null
        }
        val bitmap = loadBitmap(context.applicationContext, kind)
        if (bitmap == null) {
            missingKinds.add(kind)
            return null
        }
        bitmapCache[kind] = bitmap
        return bitmap
    }

    private fun loadBitmap(context: Context, kind: ConnectedDeviceKind): Bitmap? {
        val resourceName = RESOURCE_NAMES[kind] ?: return null
        return runCatching {
            val resources = context.packageManager.getResourcesForApplication(SOURCE_PACKAGE)
            val resourceId = resources.getIdentifier(resourceName, "drawable", SOURCE_PACKAGE)
            if (resourceId == 0) {
                return null
            }
            @Suppress("DEPRECATION")
            val drawable = resources.getDrawable(resourceId, null) ?: return null
            val rendered = Bitmap.createBitmap(
                RENDER_SIZE_PX,
                RENDER_SIZE_PX,
                Bitmap.Config.ARGB_8888
            )
            val renderCanvas = Canvas(rendered)
            drawable.setBounds(0, 0, RENDER_SIZE_PX, RENDER_SIZE_PX)
            drawable.draw(renderCanvas)
            val silhouette = Bitmap.createBitmap(
                RENDER_SIZE_PX,
                RENDER_SIZE_PX,
                Bitmap.Config.ARGB_8888
            )
            val paint = Paint().apply {
                colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
            }
            Canvas(silhouette).drawBitmap(rendered, 0f, 0f, paint)
            silhouette
        }.getOrNull()
    }
}
