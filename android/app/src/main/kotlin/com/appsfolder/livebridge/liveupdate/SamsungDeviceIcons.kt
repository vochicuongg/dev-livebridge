package com.kakao.taxi.liveupdate

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Runtime loading of Samsung's own device glyphs (round 77,
 * owner's choice): the connected-devices capsule borrows its
 * icons from the Galaxy Wearable app installed on the phone
 * (com.samsung.android.app.watchmanager), whose drawable table
 * the round-76 probe enumerated on the owner's device. Nothing
 * is copied into this app: the glyph is loaded from Samsung's
 * package at runtime and rendered AS Samsung drew it — the
 * round-78 icon sheets proved these are two-tone glyphs (a
 * white ring over gray straps for the watch, and siblings for
 * buds, fit and ring), and round 77's white-silhouette pass
 * flattened that design into a single solid blob, which is
 * exactly what the owner rejected in the expanded card. The
 * true-color render is used for the capsule's chip/Now Bar
 * icon and its large icon.
 *
 * Only four kinds have a genuine Samsung glyph: the names the
 * probe also surfaced for phone ("ic_perm_group_phone" is a
 * black telephone handset) and generic devices
 * ("ic_galaxy_wearable_for_connected_devices" is the word
 * "Wear") are wordmarks/permission icons, so phone, generic,
 * glasses, headphones and speaker keep the built-in icons.
 * Any failure — package absent, resource renamed, render
 * error — returns null and the caller falls back as well.
 */
internal object SamsungDeviceIcons {

    private const val TAG = "LiveUpdateListener"
    private const val SOURCE_PACKAGE = "com.samsung.android.app.watchmanager"
    private const val RENDER_SIZE_PX = 192

    private val RESOURCE_NAMES = mapOf(
        ConnectedDeviceKind.WATCH to "ic_watch",
        ConnectedDeviceKind.BUDS to "ic_buds",
        ConnectedDeviceKind.BAND to "ic_fit",
        ConnectedDeviceKind.RING to "ic_ring"
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
        // Round 80: rounds 77–79 loaded through
        // PackageManager.getResourcesForApplication and silently
        // got nothing — Samsung's glyphs carry theme references
        // that only resolve under the owning package's own
        // context, which is the path the round-78 probe proved
        // (46 PNGs rendered). Every outcome is logged once per
        // kind so a silent fallback can never pass as success
        // again.
        return try {
            val packageContext = context.createPackageContext(
                SOURCE_PACKAGE,
                Context.CONTEXT_IGNORE_SECURITY
            )
            val resources = packageContext.resources
            val resourceId = resources.getIdentifier(resourceName, "drawable", SOURCE_PACKAGE)
            if (resourceId == 0) {
                Log.i(TAG, "SamsungIcons: $kind resource $resourceName not found")
                return null
            }
            @Suppress("DEPRECATION")
            val drawable = resources.getDrawable(resourceId, null)
            if (drawable == null) {
                Log.i(TAG, "SamsungIcons: $kind drawable $resourceName is null")
                return null
            }
            val bitmap = Bitmap.createBitmap(
                RENDER_SIZE_PX,
                RENDER_SIZE_PX,
                Bitmap.Config.ARGB_8888
            )
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, RENDER_SIZE_PX, RENDER_SIZE_PX)
            drawable.draw(canvas)
            Log.i(TAG, "SamsungIcons: $kind loaded $resourceName")
            bitmap
        } catch (error: Exception) {
            Log.i(TAG, "SamsungIcons: $kind failed for $resourceName: $error")
            null
        }
    }
}
