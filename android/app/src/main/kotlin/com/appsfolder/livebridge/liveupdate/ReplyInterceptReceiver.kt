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
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput

/**
 * Proxy BroadcastReceiver that intercepts reply actions from Wear OS.
 *
 * ABSOLUTE LOCKDOWN + INTENT DELAY STRATEGY:
 *  1. Extracts the typed text from the RemoteInput bundle.
 *  2. Saves it to ChatHistoryStore (with Person = null for blue bubble).
 *  3. Sets a 10-second absolute lockdown so that [LiveUpdateNotifier]
 *     ignores ALL notification updates from the target app during this period.
 *  4. Calls [LiveUpdateNotifier.addLocalEchoAndRefresh] immediately to render
 *     the local echo via clone-and-inject (appends to recovered MessagingStyle).
 *  5. **CRITICAL:** Uses Handler.postDelayed(500ms) to DELAY sending the
 *     PendingIntent, giving Wear OS UI time to smoothly render before
 *     the target app receives the reply and starts its erratic behavior.
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

        /** Delay before forwarding the reply to the target app (500ms). */
        private const val INTENT_DELAY_MS = 500L

        /** Absolute lockdown duration (10 seconds total blackout). */
        private const val LOCKDOWN_DURATION_MS = 10_000L
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

        Log.d(TAG, "Intercepted reply: '$replyText' for threadKey=$threadKey, mirrorKey=$mirrorKey")

        // 2. Save the message to ChatHistoryStore (with Person = null for blue bubble).
        if (threadKey.isNotBlank()) {
            ChatHistoryStore.setPendingReply(threadKey, replyText)
            Log.d(TAG, "Saved pending reply for threadKey=$threadKey")
        }

        // 3. Activate ABSOLUTE LOCKDOWN for 10 seconds.
        // During this period, LiveUpdateNotifier will completely ignore
        // all notification events from the target app for this conversation.
        if (threadKey.isNotBlank()) {
            ChatHistoryStore.setLockdown(threadKey, LOCKDOWN_DURATION_MS)
            Log.d(TAG, "✓ LOCKDOWN ACTIVATED: threadKey=$threadKey for ${LOCKDOWN_DURATION_MS}ms")
        }

        // 4. Record reply timestamp for legacy debounce protection.
        if (mirrorKey.isNotBlank()) {
            LiveUpdateNotifier.recordReplyDebounce(mirrorKey)
        }

        // 5. FORCE the local-echo "Me" bubble onto the watch IMMEDIATELY.
        // Uses clone-and-inject: recovers the active notification's builder,
        // appends the echo to its MessagingStyle, and reposts.
        if (mirrorKey.isNotBlank()) {
            val echoMessage = NotificationCompat.MessagingStyle.Message(
                replyText,
                System.currentTimeMillis(),
                null as Person?   // null Person → right-aligned blue "Me" bubble
            )
            LiveUpdateNotifier.addLocalEchoAndRefresh(context, mirrorKey, echoMessage)
            Log.d(TAG, "✓ LOCAL ECHO RENDERED: mirrorKey=$mirrorKey")
        }

        // 6. Extract the original app's PendingIntent.
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

        // 7. **CRITICAL INTENT DELAY:** Use Handler.postDelayed to delay sending
        // the PendingIntent by 500ms. This gives Wear OS UI time to smoothly
        // render our local blue bubble BEFORE the target app receives the reply
        // and starts its erratic notification behavior (canceling, reposting, etc.).
        Handler(Looper.getMainLooper()).postDelayed({
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
                Log.d(TAG, "✓ INTENT FORWARDED (delayed ${INTENT_DELAY_MS}ms): Reply sent to target app")
            } catch (e: PendingIntent.CanceledException) {
                Log.e(TAG, "Original PendingIntent was cancelled – the source app may have removed it.", e)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to forward reply to original app.", e)
            }
        }, INTENT_DELAY_MS)

        Log.d(TAG, "Reply processing complete. Intent will be forwarded in ${INTENT_DELAY_MS}ms.")
    }
}
