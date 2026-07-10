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
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput

/**
 * Proxy BroadcastReceiver that intercepts Wear OS inline replies.
 *
 * Flow:
 * 1. Extract typed RemoteInput text.
 * 2. Store it as a local reply in ChatHistoryStore (it will be merged into
 *    the mirrored chat history when the other party replies later).
 * 3. Immediately re-post the mirrored MessagingStyle notification with the
 *    local echo injected (clone-and-inject). Gboard on Wear OS only renders
 *    the "local echo" bubble when the app calls NotificationManager.notify()
 *    on the existing notification after the reply is cached; Samsung/One UI
 *    Watch fakes this UI on its own, Gboard does not.
 * 4. Forward the original PendingIntent after 500 ms so the source app
 *    actually sends the message.
 * 5. After the PendingIntent is handed to the source app, wait
 *    [DISMISS_DELAY_MS] (so the updated UI from step 3 stays visible and the
 *    notify() has completed) and only then dismiss both the LiveBridge
 *    mirror and the original phone notification.
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

        /**
         * Delay between forwarding the reply to the source app and cancelling
         * the mirror/source notifications. Keeps the re-posted local-echo UI
         * on screen long enough for Gboard to render it and guarantees the
         * cancel always runs AFTER the notify() from the local-echo update.
         * Must stay within goAsync()'s ~10 s window (500 + 1500 = 2000 ms).
         */
        private const val DISMISS_DELAY_MS = 1_500L
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
            Log.d(TAG, "Reply cached for threadKey=$threadKey")

            // GBOARD LOCAL ECHO FIX: Gboard (unlike Samsung keyboard / One UI
            // Watch) does not fake a temporary "sent" UI. It only renders the
            // local echo when the app re-posts the SAME notification id via
            // NotificationManager.notify() after the reply is cached. We use
            // the clone-and-inject pattern (recoverBuilder on the cached
            // active notification) so OEM extras, the notification channel
            // and Person identity are preserved. threadKey here was produced
            // by LiveUpdateNotifier.threadKeyForNotification(sbn) ->
            // buildThreadKey(packageName, conversationTitle), i.e. the exact
            // same builder logic used for history caching/grouping, so the
            // merged chat history stays consistent.
            val echoPosted = republishMirrorWithLocalEcho(
                context = context.applicationContext,
                threadKey = threadKey,
                mirrorNotificationId = mirrorNotificationId,
                replyText = replyText
            )
            Log.d(
                TAG,
                "Local echo notify posted=$echoPosted for id=$mirrorNotificationId, threadKey=$threadKey"
            )
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
        val mainHandler = Handler(Looper.getMainLooper())
        mainHandler.postDelayed({
            var dismissScheduled = false
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

                // Delay the mirror/source cancellation so it always happens
                // AFTER the local-echo notify() has been rendered by Gboard.
                // pendingResult.finish() is only called once the dismissal
                // completes, keeping the process alive for the whole delay
                // (total 500 + 1800 = 2300 ms, well inside goAsync's window).
                dismissScheduled = true
                mainHandler.postDelayed({
                    try {
                        dismissAfterSuccessfulReply(
                            context = context.applicationContext,
                            mirrorKey = mirrorKey,
                            mirrorNotificationId = mirrorNotificationId,
                            sourceKey = sourceKey.ifBlank { mirrorKey }
                        )
                        Log.d(TAG, "Mirror/source dismissed after ${DISMISS_DELAY_MS}ms delay")
                    } catch (error: Throwable) {
                        Log.e(TAG, "Delayed dismissal failed.", error)
                    } finally {
                        pendingResult.finish()
                    }
                }, DISMISS_DELAY_MS)
            } catch (e: PendingIntent.CanceledException) {
                Log.e(TAG, "Original PendingIntent was cancelled.", e)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to forward reply to original app.", e)
            } finally {
                if (!dismissScheduled) {
                    pendingResult.finish()
                }
            }
        }, INTENT_DELAY_MS)
    }

    /**
     * Re-posts the currently displayed mirrored notification with the typed
     * reply appended as a right-aligned "Me" bubble (local echo).
     *
     * Uses the clone-and-inject pattern: recover the builder from the active
     * notification currently shown on the watch (preserving channel, OEM extras
     * and Person identity), append the local message to its MessagingStyle and
     * call NotificationManager.notify() with the EXACT mirror notification id.
     * This is what makes Gboard render the local echo immediately.
     *
     * @return true if an updated notification was posted.
     */
    private fun republishMirrorWithLocalEcho(
        context: Context,
        threadKey: String,
        mirrorNotificationId: Int,
        replyText: String
    ): Boolean {
        if (mirrorNotificationId == INVALID_NOTIFICATION_ID) {
            Log.w(TAG, "No valid mirror notification id; skipping local echo notify.")
            return false
        }

        return try {
            // 1. Get NotificationManager and find the active notification
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            if (notificationManager == null) {
                Log.e(TAG, "NotificationManager unavailable; cannot post local echo.")
                return false
            }

            // 2. Find the active notification with matching ID on the watch
            val activeNotifications = notificationManager.activeNotifications
            val activeSbn = activeNotifications.firstOrNull { it.id == mirrorNotificationId }
            
            if (activeSbn == null) {
                Log.w(TAG, "No active notification found with id=$mirrorNotificationId; skipping local echo.")
                return false
            }

            // 3. Get the original Notification and extract its MessagingStyle
            val activeNotif = activeSbn.notification
            val style = NotificationCompat.MessagingStyle
                .extractMessagingStyleFromNotification(activeNotif)
            if (style == null) {
                Log.w(TAG, "Active notification has no MessagingStyle; skipping local echo notify.")
                return false
            }

            // 4. Add the reply message as "Me" (null Person = current user)
            // null Person renders as right-aligned bubble on Wear OS
            style.addMessage(replyText, System.currentTimeMillis(), null as Person?)

            // 5. Recover the builder and rebuild with updated style
            val builder = NotificationCompat.Builder.recoverBuilder(context, activeNotif)
                .setStyle(style)
                .setOnlyAlertOnce(true)

            // 6. Build and post the updated notification with the same ID
            val updatedNotification = builder.build()
            notificationManager.notify(mirrorNotificationId, updatedNotification)
            
            // Keep the cache in sync so any later clone-and-inject pass
            // starts from the notification that is actually on screen.
            ChatHistoryStore.setActiveNotification(threadKey, updatedNotification)
            
            Log.d(TAG, "Successfully posted local echo for notification id=$mirrorNotificationId")
            true
        } catch (error: Throwable) {
            Log.e(TAG, "Failed to re-post mirrored notification with local echo.", error)
            false
        }
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
