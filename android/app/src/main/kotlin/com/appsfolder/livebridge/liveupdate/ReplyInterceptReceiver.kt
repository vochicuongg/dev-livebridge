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
 * When the user replies via RemoteInput on the watch, this receiver:
 *  1. Extracts the typed text from the RemoteInput bundle.
 *  2. Injects a local-echo "Me" message into the conversation cache so that
 *     the watch UI instantly reflects the sent message.
 *  3. Forwards the RemoteInput text to the original app's PendingIntent so
 *     the message is actually delivered (e.g. to Zalo, Messenger, etc.).
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
            Log.w(TAG, "Empty reply text, skipping local echo and forward.")
            return
        }

        val mirrorKey = intent.getStringExtra(EXTRA_MIRROR_KEY).orEmpty()

        // 2. Create a local-echo message attributed to "Me".
        // CRITICAL: Use the singleton LOCAL_USER_ME from LiveUpdateNotifier to ensure
        // consistent Person identity across all operations. This guarantees that the
        // echo message will render on the RIGHT side of Wear OS chat bubbles.
        val echoMessage = NotificationCompat.MessagingStyle.Message(
            replyText,
            System.currentTimeMillis(),
            LiveUpdateNotifier.LOCAL_USER_ME
        )

        // 3. Inject into the local cache and refresh the watch notification
        //    (without vibrating).
        if (mirrorKey.isNotBlank()) {
            try {
                LiveUpdateNotifier.addLocalEchoAndRefresh(context, mirrorKey, echoMessage)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to inject local echo for mirrorKey=$mirrorKey", e)
            }
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
            // Build a new Intent and pack the RemoteInput result into it so that
            // the original app receives the text just as if the user had replied
            // directly from its own notification.
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
