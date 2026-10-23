package com.kakao.taxi.liveupdate

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Forwards a progress mirror's action (e.g. the Play Store's
 * Cancel) to the source app and time-stamps the tap (round 66).
 * The store never deletes its snoozed record when a download is
 * cancelled, so the tap plus a record that never changes
 * afterwards is the only reliable end-of-download signal.
 */
class ProgressActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val sourceKey = intent.getStringExtra(EXTRA_SOURCE_KEY)?.takeIf { it.isNotBlank() }
            ?: return
        val originalIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_ORIGINAL_PENDING_INTENT, PendingIntent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_ORIGINAL_PENDING_INTENT)
        }
        LiveUpdateNotifier.noteProgressActionTap(sourceKey)
        try {
            originalIntent?.send()
        } catch (error: Throwable) {
            Log.w(TAG, "Progress action intent failed for $sourceKey", error)
        }
    }

    companion object {
        const val ACTION_PROGRESS_ACTION = "com.kakao.taxi.action.PROGRESS_ACTION"
        const val EXTRA_ORIGINAL_PENDING_INTENT = "original_pending_intent"
        const val EXTRA_SOURCE_KEY = "source_key"
        private const val TAG = "ProgressActionReceiver"
    }
}
