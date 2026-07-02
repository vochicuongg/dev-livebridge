package com.kakao.taxi.liveupdate

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
 * 2. Store it as a local reply in ChatHistoryStore.
 * 3. Immediately force a deterministic synthetic MessagingStyle rebuild.
 * 4. Lock the thread for 10 seconds.
 * 5. Forward the original PendingIntent after 500 ms.
 */
class ReplyInterceptReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ReplyInterceptReceiver"

        const val ACTION_PROXY_REPLY = "com.kakao.taxi.action.PROXY_REPLY"
        const val EXTRA_ORIGINAL_PENDING_INTENT = "original_pending_intent"
        const val EXTRA_MIRROR_KEY = "mirror_key"
        const val EXTRA_RESULT_KEY = "result_key"
        const val EXTRA_THREAD_KEY = "thread_key"

        private const val INTENT_DELAY_MS = 500L
        private const val LOCKDOWN_DURATION_MS = 10_000L
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

        Log.d(TAG, "Intercepted reply for threadKey=$threadKey, mirrorKey=$mirrorKey")

        if (threadKey.isNotBlank()) {
            ChatHistoryStore.setPendingReply(threadKey, replyText)
            ChatHistoryStore.appendLocalReply(threadKey, replyText)
            LiveUpdateNotifier.forceUpdateChatUi(context, threadKey)
            ChatHistoryStore.setLockdown(threadKey, LOCKDOWN_DURATION_MS)
            Log.d(TAG, "Local echo rendered and lockdown activated for threadKey=$threadKey")
        }

        if (mirrorKey.isNotBlank()) {
            LiveUpdateNotifier.recordReplyDebounce(mirrorKey)
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
            } catch (e: PendingIntent.CanceledException) {
                Log.e(TAG, "Original PendingIntent was cancelled.", e)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to forward reply to original app.", e)
            }
        }, INTENT_DELAY_MS)
    }
}
