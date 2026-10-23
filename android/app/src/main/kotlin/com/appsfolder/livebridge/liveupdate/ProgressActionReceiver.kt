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
        val actionTitle = intent.getStringExtra(EXTRA_ACTION_TITLE).orEmpty()
        try {
            originalIntent?.send()
        } catch (error: Throwable) {
            Log.w(TAG, "Progress action intent failed for $sourceKey", error)
        }
        LiveUpdateNotifier.noteProgressActionTap(sourceKey)
        Log.i(TAG, "Progress action tap recorded: $sourceKey")
        // A Cancel tap ends the download by the user's own hand:
        // retire the mirror immediately instead of waiting out the
        // frozen-record suspicion window (round 69, owner request).
        // Pause/Resume titles never match, so a paused download
        // keeps its capsule and the record's state change clears
        // the tap suspicion as before.
        if (isCancelActionTitle(actionTitle)) {
            Log.i(TAG, "Progress cancel tap: retire mirror now: $sourceKey")
            LiveUpdateNotificationListenerService.activeInstance
                ?.retireProgressSourceNow(sourceKey)
        }
    }

    companion object {
        const val ACTION_PROGRESS_ACTION = "com.kakao.taxi.action.PROGRESS_ACTION"
        const val EXTRA_ORIGINAL_PENDING_INTENT = "original_pending_intent"
        const val EXTRA_SOURCE_KEY = "source_key"
        const val EXTRA_ACTION_TITLE = "action_title"
        private const val TAG = "ProgressActionReceiver"

        private val CANCEL_TITLE_MARKERS = listOf(
            "cancel", "hủy", "huỷ", "отмен", "iptal", "cancelar",
            "abbrechen", "annuler", "annulla", "取消", "キャンセル",
            "취소", "batal", "annuleren", "avbryt", "anuluj"
        )

        fun isCancelActionTitle(title: String): Boolean {
            val normalized = title.trim().lowercase()
            if (normalized.isEmpty()) return false
            return CANCEL_TITLE_MARKERS.any { marker -> normalized.contains(marker) }
        }
    }
}
