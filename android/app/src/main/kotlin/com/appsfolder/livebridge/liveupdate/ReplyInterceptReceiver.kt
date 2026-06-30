package com.kakao.taxi.liveupdate

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.core.app.RemoteInput

/**
 * Proxy BroadcastReceiver that intercepts reply actions from Wear OS.
 *
 * Stateful Native Mirroring approach:
 *  1. Extracts the typed text from the RemoteInput bundle.
 *  2. Saves it as a pending reply in [ChatHistoryStore] so that
 *     [LiveUpdateNotifier] can inject it on the next onNotificationPosted.
 *  3. Forwards the RemoteInput text to the original app's PendingIntent.
 *  4. Does NOT call any UI update (notify) – lets the target app do it.
 */
class ReplyInterceptReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ReplyInterceptReceiver"

        /** Action fired by the proxy PendingIntent created in toCompatAction. */
        const val ACTION_PROXY_REPLY = "com.kakao.taxi.action.PROXY_REPLY"

        /** Extra key: the original app's PendingIntent we must forward the reply to. */
        const val EXTRA_ORIGINAL_PENDING_INTENT = "original_pending_intent"

        /** Extra key: the mirrorKey identifying the mirrored conversation. */
        const val EXTRA_MIRROR_KEY = "mirror_key"

        /** Extra key: the RemoteInput result key used by the original app. */
        const val EXTRA_RESULT_KEY = "result_key"

        /** Extra key: the thread key for ChatHistoryStore. */
        const val EXTRA_THREAD_KEY = "thread_key"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null || intent.action != ACTION_PROXY_REPLY) {
            return
        }

        // 1. Extract the RemoteInput text typed by the user on the watch.
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

        // 2. Save pending reply state so LiveUpdateNotifier can inject it
        //    when the target app's next notification update arrives.
        if (threadKey.isNotBlank()) {
            ChatHistoryStore.setPendingReply(threadKey, replyText)
            Log.d(TAG, "Saved pending reply for threadKey=$threadKey: '$replyText'")
        }

        // 3. Record reply timestamp for debounce protection against instant deletion.
        if (mirrorKey.isNotBlank()) {
            LiveUpdateNotifier.recordReplyDebounce(mirrorKey)
        }

        // 4. Forward the reply text to the original app's PendingIntent.
        val originalPendingIntent: PendingIntent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_ORIGINAL_PENDING_INTENT, PendingIntent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_ORIGINAL_PENDING_INTENT)
        }

        if (originalPendingIntent == null) {
            Log.e(TAG, "Original PendingIntent is null – cannot forward reply.")
            return
        }

        try {
            val forwardIntent = Intent()
            val resultBundle = Bundle().apply {
                putCharSequence(resultKey, replyText)
            }
            RemoteInput.addResultsToIntent(
                arrayOf(
                    RemoteInput.Builder(resultKey).build()
                ),
                forwardIntent,
                resultBundle
            )
            originalPendingIntent.send(context, 0, forwardIntent)
        } catch (e: PendingIntent.CanceledException) {
            Log.e(TAG, "Original PendingIntent was cancelled – the source app may have removed it.", e)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to forward reply to original app.", e)
        }
    }
}
