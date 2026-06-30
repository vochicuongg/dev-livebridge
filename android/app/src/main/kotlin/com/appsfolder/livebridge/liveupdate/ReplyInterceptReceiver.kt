package com.kakao.taxi.liveupdate

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput

/**
 * Proxy BroadcastReceiver that intercepts reply actions from Wear OS.
 *
 * Absolute Local Truth + UI Lock approach:
 *  1. Extracts the typed text from the RemoteInput bundle.
 *  2. Saves it as a pending reply in [ChatHistoryStore].
 *  3. Sets a [ChatHistoryStore.setUiLock] so that [LiveUpdateNotifier]
 *     drops any state-downgrade notifications for 6 seconds.
 *  4. Calls [LiveUpdateNotifier.addLocalEchoAndRefresh] to FORCE the
 *     "Me" bubble onto the watch immediately from the cache.
 *  5. Forwards the RemoteInput text to the original app's PendingIntent.
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

        // 2. Save pending reply + activate UI lock so LiveUpdateNotifier
        //    drops any state-downgrade from the target app for 6 seconds.
        if (threadKey.isNotBlank()) {
            ChatHistoryStore.setPendingReply(threadKey, replyText)
            ChatHistoryStore.setUiLock(threadKey)
            Log.d(TAG, "Saved pending reply + UI lock for threadKey=$threadKey: '$replyText'")
        }

        // 3. Record reply timestamp for debounce protection against instant deletion.
        if (mirrorKey.isNotBlank()) {
            LiveUpdateNotifier.recordReplyDebounce(mirrorKey)
        }

        // 4. FORCE the local-echo "Me" bubble onto the watch immediately
        //    from the cache – do NOT wait for onNotificationPosted.
        if (mirrorKey.isNotBlank()) {
            val echoMessage = NotificationCompat.MessagingStyle.Message(
                replyText,
                System.currentTimeMillis(),
                null as Person?   // null Person → right-aligned blue "Me" bubble
            )
            LiveUpdateNotifier.addLocalEchoAndRefresh(context, mirrorKey, echoMessage)
            Log.d(TAG, "Triggered addLocalEchoAndRefresh for mirrorKey=$mirrorKey")
        }

        // 5. Forward the reply text to the original app's PendingIntent.
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
