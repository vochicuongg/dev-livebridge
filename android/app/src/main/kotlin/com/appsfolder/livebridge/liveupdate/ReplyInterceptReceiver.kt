package com.kakao.taxi.liveupdate

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.RemoteInput

/**
 * Proxy BroadcastReceiver that intercepts Wear OS inline replies.
 *
 * Flow:
 * 1. Extract typed RemoteInput text.
 * 2. Store it as a local reply in ChatHistoryStore (it will be merged into
 *    the mirrored chat history when the other party replies later).
 * 3. Forward the original PendingIntent after 500 ms so the source app
 *    actually sends the message.
 * 4. After the PendingIntent is handed to the source app, dismiss both the
 *    LiveBridge mirror and the original phone notification.
 */
class ReplyInterceptReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ReplyInterceptReceiver"

        const val ACTION_PROXY_REPLY = "com.kakao.taxi.action.PROXY_REPLY"
        const val EXTRA_ORIGINAL_PENDING_INTENT = "original_pending_intent"
        const val EXTRA_MIRROR_KEY = "mirror_key"
        const val EXTRA_RESULT_KEY = "result_key"
        const val EXTRA_THREAD_KEY = "thread_key"
        const val EXTRA_MIRROR_NOTIFICATION_ID = "mirror_notification_id"
        const val EXTRA_SOURCE_KEY = "source_key"

        private const val INTENT_DELAY_MS = 500L
        private const val INVALID_NOTIFICATION_ID = Int.MIN_VALUE
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null || intent.action != ACTION_PROXY_REPLY) {
            return
        }

        val remoteInputResults = RemoteInput.getResultsFromIntent(intent)
        val resultKey = intent.getStringExtra(EXTRA_RESULT_KEY).orEmpty()
        val replyText = remoteInputResults
            ?.getCharSequence(resultKey)
            ?.toString()
            .orEmpty()
            .trim()

        if (replyText.isBlank()) {
            Log.w(TAG, "Empty reply text, skipping.")
            return
        }

        val mirrorKey = intent.getStringExtra(EXTRA_MIRROR_KEY).orEmpty()
        val threadKey = intent.getStringExtra(EXTRA_THREAD_KEY).orEmpty()
        val mirrorNotificationId = intent.getIntExtra(
            EXTRA_MIRROR_NOTIFICATION_ID,
            INVALID_NOTIFICATION_ID
        )
        val sourceKey = intent.getStringExtra(EXTRA_SOURCE_KEY).orEmpty()

        Log.d(TAG, "Intercepted reply for threadKey=$threadKey, mirrorKey=$mirrorKey")

        if (threadKey.isNotBlank()) {
            ChatHistoryStore.setPendingReply(threadKey, replyText)
            ChatHistoryStore.appendLocalReply(threadKey, replyText)
            // NOTE: Deliberately NO UI rebuild here (no forceUpdateChatUi /
            // notify). Re-posting the notification while the watch is in the
            // "Sending..." state keeps the reply UI stuck in a loading loop.
            // The cached history above is picked up automatically by the
            // mirrored-notification builder when the next incoming message
            // arrives from the other party.
            Log.d(TAG, "Reply cached for threadKey=$threadKey")
        }

        val originalPendingIntent: PendingIntent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_ORIGINAL_PENDING_INTENT, PendingIntent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_ORIGINAL_PENDING_INTENT)
        }

        if (originalPendingIntent == null) {
            Log.e(TAG, "Original PendingIntent is null; cannot forward reply.")
            return
        }

        val pendingResult = goAsync()
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                val forwardIntent = Intent()
                val resultBundle = Bundle().apply {
                    putCharSequence(resultKey, replyText)
                }
                RemoteInput.addResultsToIntent(
                    arrayOf(RemoteInput.Builder(resultKey).build()),
                    forwardIntent,
                    resultBundle
                )
                originalPendingIntent.send(context, 0, forwardIntent)
                Log.d(TAG, "Intent forwarded after ${INTENT_DELAY_MS}ms")
                dismissAfterSuccessfulReply(
                    context = context.applicationContext,
                    mirrorKey = mirrorKey,
                    mirrorNotificationId = mirrorNotificationId,
                    sourceKey = sourceKey.ifBlank { mirrorKey }
                )
            } catch (e: PendingIntent.CanceledException) {
                Log.e(TAG, "Original PendingIntent was cancelled.", e)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to forward reply to original app.", e)
            } finally {
                pendingResult.finish()
            }
        }, INTENT_DELAY_MS)
    }

    private fun dismissAfterSuccessfulReply(
        context: Context,
        mirrorKey: String,
        mirrorNotificationId: Int,
        sourceKey: String
    ) {
        LiveUpdateNotificationListenerService.rememberProgrammaticReplyCancel(
            sourceKey = sourceKey,
            mirrorKey = mirrorKey,
            mirrorNotificationId = mirrorNotificationId
        )
        dismissMirrorNotification(context, mirrorKey, mirrorNotificationId)
        dismissSourceNotification(context, sourceKey)
    }

    private fun dismissMirrorNotification(
        context: Context,
        mirrorKey: String,
        mirrorNotificationId: Int
    ) {
        try {
            if (mirrorKey.isNotBlank()) {
                LiveUpdateNotifier.cancelMirroredForReply(context, mirrorKey)
            }
            if (mirrorNotificationId != INVALID_NOTIFICATION_ID) {
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                manager?.cancel(mirrorNotificationId)
                Log.d(TAG, "Mirror notification cancelled directly: id=$mirrorNotificationId")
            }
        } catch (error: Throwable) {
            Log.e(TAG, "Failed to cancel mirror notification after reply.", error)
        }
    }

    private fun dismissSourceNotification(context: Context, sourceKey: String) {
        val normalizedSourceKey = sourceKey.trim()
        if (normalizedSourceKey.isBlank()) {
            return
        }

        try {
            val listener = LiveUpdateNotificationListenerService.activeInstance
            if (listener != null) {
                listener.cancelNotification(normalizedSourceKey)
                Log.d(TAG, "Source notification cancelled directly: $normalizedSourceKey")
            } else {
                LiveUpdateNotificationListenerService.requestCancelSourceNotification(
                    context = context.applicationContext,
                    sourceKey = normalizedSourceKey
                )
            }
        } catch (error: Throwable) {
            Log.e(TAG, "Failed to cancel source notification after reply: $normalizedSourceKey", error)
        }
    }
}
