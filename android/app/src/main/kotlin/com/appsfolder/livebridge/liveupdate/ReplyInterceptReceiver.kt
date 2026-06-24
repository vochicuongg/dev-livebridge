package com.kakao.taxi.liveupdate

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput

/**
 * Proxy BroadcastReceiver that intercepts reply actions from Wear OS.
 *
 * Flow:
 * 1. User types a reply on Wear OS → OS fires this receiver via the proxy PendingIntent.
 * 2. We extract the reply text from [RemoteInput].
 * 3. **Local echo**: Delegate to [LiveUpdateNotifier.addLocalEchoAndRefresh] which:
 *    - Creates a "Me" [Person] message appended to the conversation cache.
 *    - Rebuilds the full mirrored notification through the standard pipeline
 *      ([buildMirroredNotification]) so the chat bubble appears on the RIGHT side.
 *    - Re-notifies with the SAME notification ID so the card stays in place
 *      (no dismiss, no extra vibration).
 * 4. **Forward**: Send the reply to the original app's [PendingIntent].
 *
 * This ensures the user sees their message instantly in the notification
 * without waiting for the remote party to respond.
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
        if (intent == null || intent.action != ACTION_PROXY_REPLY) return

        val mirrorKey = intent.getStringExtra(EXTRA_MIRROR_KEY).orEmpty()
        val resultKey = intent.getStringExtra(EXTRA_RESULT_KEY).orEmpty()

        // Extract the reply text from RemoteInput results
        val replyText = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(resultKey)
            ?.toString()
            .orEmpty()
            .trim()

        if (replyText.isBlank()) {
            Log.w(TAG, "Empty reply text – ignoring")
            return
        }

        Log.d(TAG, "Received reply for mirrorKey=$mirrorKey, text=$replyText")

        // ── 1) Local echo: update the notification in-place ────────────
        // Delegate to LiveUpdateNotifier which owns the conversation cache,
        // notification building pipeline, and the mirrorKey→notificationId map.
        // This guarantees:
        //   • The "Me" message appears on the RIGHT (sender = LOCAL_USER_ME).
        //   • The notification is rebuilt with all original actions (including Reply).
        //   • The same notification ID is reused → card stays, no vibration.
        if (mirrorKey.isNotBlank()) {
            try {
                val me: Person = LiveUpdateNotifier.LOCAL_USER_ME
                val echoMessage = NotificationCompat.MessagingStyle.Message(
                    replyText,
                    System.currentTimeMillis(),
                    me
                )
                LiveUpdateNotifier.addLocalEchoAndRefresh(context, mirrorKey, echoMessage)
                Log.d(TAG, "Local echo posted for mirrorKey=$mirrorKey")
            } catch (t: Throwable) {
                Log.e(TAG, "addLocalEchoAndRefresh failed for mirrorKey=$mirrorKey", t)
            }
        }

        // ── 2) Forward the reply to the original app ───────────────────
        forwardReplyToOriginalApp(context, intent, resultKey, replyText)
    }

    /**
     * Sends the user's reply text to the original app via its [PendingIntent].
     *
     * We rebuild a minimal [Intent] carrying the [RemoteInput] results bundle
     * so the source app (KakaoTalk, Zalo, Telegram, …) processes the reply
     * as if it came from the system's inline-reply UI.
     */
    private fun forwardReplyToOriginalApp(
        context: Context,
        originalIntent: Intent,
        resultKey: String,
        replyText: String,
    ) {
        try {
            @Suppress("DEPRECATION")
            val originalPendingIntent: PendingIntent? =
                originalIntent.getParcelableExtra(EXTRA_ORIGINAL_PENDING_INTENT)

            if (originalPendingIntent == null) {
                Log.w(TAG, "No original PendingIntent found; cannot forward reply")
                return
            }

            val forwardIntent = Intent().apply {
                val bundle = Bundle()
                if (resultKey.isNotBlank()) {
                    bundle.putCharSequence(resultKey, replyText)
                }
                RemoteInput.addResultsToIntent(
                    arrayOf(RemoteInput.Builder(resultKey).build()),
                    this,
                    bundle,
                )
            }

            originalPendingIntent.send(context, 0, forwardIntent)
            Log.d(TAG, "Reply forwarded to original app")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to forward reply to original app", t)
        }
    }
}
