package com.kakao.taxi.liveupdate

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Round 135: a Like tap on a chat mirror fires the source
 * app's own action first (so Instagram's Like still sends
 * the react the other party sees), then dismisses the
 * mirror — the Now Bar card and its shade copy — the way
 * Facebook's Like dismisses its own notification. The
 * source notification itself is NOT cancelled: the
 * conversation stays alive in the source app, and a new
 * message brings the mirror back. If the original intent
 * fails (already consumed), the mirror is left alone so a
 * dead tap does not silently eat the card.
 */
class MirrorLikeActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val mirrorKey = intent.getStringExtra(EXTRA_MIRROR_KEY)?.takeIf { it.isNotBlank() } ?: return
        val originalIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_ORIGINAL_PENDING_INTENT, PendingIntent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_ORIGINAL_PENDING_INTENT)
        } ?: return
        try {
            originalIntent.send()
        } catch (error: Throwable) {
            Log.w(TAG, "Like action intent failed for $mirrorKey", error)
            return
        }
        LiveUpdateNotifier.cancelMirroredForLike(context, mirrorKey)
        Log.i(TAG, "Like tap: mirror dismissed: $mirrorKey")
    }

    companion object {
        const val ACTION_MIRROR_LIKE = "com.kakao.taxi.action.MIRROR_LIKE"
        const val EXTRA_ORIGINAL_PENDING_INTENT = "original_pending_intent"
        const val EXTRA_MIRROR_KEY = "mirror_key"
        const val EXTRA_ACTION_TITLE = "action_title"
        private const val TAG = "MirrorLikeAction"
    }
}
