package com.kakao.taxi.liveupdate

import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.graphics.drawable.IconCompat
import com.kakao.taxi.R
import java.util.concurrent.Executors

/**
 * Service that intercepts Wear OS inline replies.
 * Uses Service instead of BroadcastReceiver because Service
 * can properly receive RemoteInput results via startService intent.
 */
class ReplyProxyService : Service() {

    private val executor = Executors.newSingleThreadExecutor()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand called, action=${intent?.action}")
        
        if (intent != null) {
            executor.execute {
                try {
                    handleIntent(intent)
                } finally {
                    stopSelf(startId)
                }
            }
        } else {
            stopSelf(startId)
        }
        
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdown()
    }

    companion object {
        private const val TAG = "ReplyProxyService"

        const val ACTION_PROXY_REPLY = "com.kakao.taxi.action.PROXY_REPLY"
        const val EXTRA_ORIGINAL_PENDING_INTENT = "original_pending_intent"
        const val EXTRA_MIRROR_KEY = "mirror_key"
        const val EXTRA_RESULT_KEY = "result_key"
        const val EXTRA_THREAD_KEY = "thread_key"
        const val EXTRA_MIRROR_NOTIFICATION_ID = "mirror_notification_id"
        const val EXTRA_SOURCE_KEY = "source_key"
        const val EXTRA_REPLY_LABEL = "reply_label"
        const val EXTRA_REPLY_HINT = "reply_hint"
        const val EXTRA_REPLY_CONVERSATION = "reply_conversation"
        const val EXTRA_REPLY_CONTEXT_TEXT = "reply_context_text"
        const val EXTRA_REPLY_SOURCE_PACKAGE = "reply_source_package"
        const val EXTRA_REPLY_ACCENT_COLOR = "reply_accent_color"
        const val EXTRA_REPLY_OTHER_ACTION_TITLES = "reply_other_action_titles"
        const val EXTRA_REPLY_OTHER_ACTION_INTENTS = "reply_other_action_intents"

        /**
         * Forward attempts for the original PendingIntent. Round 137
         * removed the old 500 ms pre-send delay (the Wear reply loss
         * window); a transient failure is retried after
         * [FORWARD_RETRY_DELAY_MS]. A cancelled PendingIntent is never
         * retried.
         */
        private const val FORWARD_MAX_ATTEMPTS = 3
        private const val FORWARD_RETRY_DELAY_MS = 250L

        /** WakeLock ceiling for the whole handle → forward → dismiss flow. */
        private const val WAKE_LOCK_TIMEOUT_MS = 10_000L

        private const val DISMISS_DELAY_MS = 1_500L
        private const val INVALID_NOTIFICATION_ID = Int.MIN_VALUE
    }

    private fun handleIntent(intent: Intent) {
        Log.d(TAG, "handleIntent called, action=${intent.action}")
        if (intent.action != ACTION_PROXY_REPLY) {
            Log.w(TAG, "Intent null or wrong action, returning")
            return
        }

        // Extract RemoteInput results from the intent
        val remoteInputResults = RemoteInput.getResultsFromIntent(intent)
        val resultKey = intent.getStringExtra(EXTRA_RESULT_KEY).orEmpty()
        
        Log.d(TAG, "resultKey from intent: '$resultKey'")
        Log.d(TAG, "remoteInputResults: $remoteInputResults")
        
        // Debug: print all keys in remoteInputResults
        if (remoteInputResults != null) {
            Log.d(TAG, "Available keys in remoteInputResults: ${remoteInputResults.keySet()}")
            remoteInputResults.keySet().forEach { key ->
                Log.d(TAG, "  key='$key' -> value='${remoteInputResults.getCharSequence(key)}'")
            }
        }
        
        val replyText = remoteInputResults
            ?.getCharSequence(resultKey)
            ?.toString()
            .orEmpty()
            .trim()

        if (replyText.isBlank()) {
            Log.w(TAG, "Empty reply text, skipping. resultKey='$resultKey', remoteInputResults=$remoteInputResults")
            return
        }

        val mirrorKey = intent.getStringExtra(EXTRA_MIRROR_KEY).orEmpty()
        val threadKey = intent.getStringExtra(EXTRA_THREAD_KEY).orEmpty()
        val mirrorNotificationId = intent.getIntExtra(
            EXTRA_MIRROR_NOTIFICATION_ID,
            INVALID_NOTIFICATION_ID
        )
        val sourceKey = intent.getStringExtra(EXTRA_SOURCE_KEY).orEmpty()

        Log.d(TAG, "Intercepted reply for threadKey=$threadKey, mirrorKey=$mirrorKey, replyText='$replyText'")

        if (threadKey.isNotBlank()) {
            ChatHistoryStore.setPendingReply(threadKey, replyText)
            ChatHistoryStore.appendLocalReply(threadKey, replyText)
            Log.d(TAG, "Reply cached for threadKey=$threadKey")

            val echoPosted = republishMirrorWithLocalEcho(
                context = applicationContext,
                threadKey = threadKey,
                mirrorNotificationId = mirrorNotificationId,
                replyText = replyText
            )
            Log.d(TAG, "Local echo posted=$echoPosted for id=$mirrorNotificationId")
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

        // Round 137: forward immediately (no 500 ms pre-send delay) under
        // a WakeLock, with retries. The dismiss still waits
        // DISMISS_DELAY_MS after a successful forward, and only then; a
        // failed forward leaves the mirror up so the user can retry
        // instead of believing the message was sent.
        val wakeLock = acquireWakeLock(applicationContext)
        try {
            PendingReplyStore.savePending(
                context = applicationContext,
                threadKey = threadKey,
                mirrorKey = mirrorKey,
                sourceKey = sourceKey,
                mirrorNotificationId = mirrorNotificationId,
                resultKey = resultKey,
                text = replyText
            )
            val forwardIntent = Intent()
            val resultBundle = Bundle().apply {
                putCharSequence(resultKey, replyText)
            }
            RemoteInput.addResultsToIntent(
                arrayOf(RemoteInput.Builder(resultKey).build()),
                forwardIntent,
                resultBundle
            )
            var sent = false
            var attempt = 0
            while (!sent && attempt < FORWARD_MAX_ATTEMPTS) {
                attempt++
                try {
                    originalPendingIntent.send(applicationContext, 0, forwardIntent)
                    sent = true
                    Log.d(TAG, "Reply forwarded to source app (attempt $attempt)")
                } catch (e: PendingIntent.CanceledException) {
                    Log.e(TAG, "Original PendingIntent was cancelled; reply not sent.", e)
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "Forward attempt $attempt failed, retrying...", e)
                    if (attempt < FORWARD_MAX_ATTEMPTS) {
                        runCatching { Thread.sleep(FORWARD_RETRY_DELAY_MS) }
                    }
                }
            }
            if (!sent) {
                Log.e(
                    TAG,
                    "Failed to forward reply after $FORWARD_MAX_ATTEMPTS attempts; mirror left up so the user can retry."
                )
                PendingReplyStore.markFailed(applicationContext, threadKey)
                return
            }
            PendingReplyStore.markSent(applicationContext, threadKey)

            // Wait before dismissing notifications
            Thread.sleep(DISMISS_DELAY_MS)

            dismissAfterSuccessfulReply(
                context = applicationContext,
                mirrorKey = mirrorKey,
                mirrorNotificationId = mirrorNotificationId,
                sourceKey = sourceKey.ifBlank { mirrorKey }
            )
            Log.d(TAG, "Mirror/source notifications dismissed")
        } finally {
            runCatching {
                if (wakeLock?.isHeld == true) wakeLock.release()
            }
        }
    }

    /**
     * Holds a PARTIAL_WAKE_LOCK for the handle → forward → dismiss flow
     * so Doze / screen-off cannot freeze the dismiss sleep mid-reply.
     * The lock has a hard timeout ([WAKE_LOCK_TIMEOUT_MS]) so the system
     * releases it even if this service is killed.
     */
    private fun acquireWakeLock(context: Context): PowerManager.WakeLock? {
        return try {
            val powerManager =
                context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                    ?: return null
            powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "livebridge:PhoneReply"
            ).apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
        } catch (error: Throwable) {
            Log.w(TAG, "Could not acquire WakeLock; continuing without it.", error)
            null
        }
    }

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
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            if (notificationManager == null) {
                Log.e(TAG, "NotificationManager unavailable; cannot post local echo.")
                return false
            }

            val activeNotifications = notificationManager.activeNotifications
            val activeSbn = activeNotifications.firstOrNull { it.id == mirrorNotificationId }

            if (activeSbn == null) {
                Log.w(TAG, "No active notification found with id=$mirrorNotificationId; skipping local echo.")
                return false
            }

            val activeNotif = activeSbn.notification
            val style = NotificationCompat.MessagingStyle
                .extractMessagingStyleFromNotification(activeNotif)
            if (style == null) {
                Log.w(TAG, "Active notification has no MessagingStyle; skipping local echo notify.")
                return false
            }

            style.addMessage(replyText, System.currentTimeMillis(), null as Person?)

            val channelId = activeNotif.channelId ?: LiveUpdateNotifier.CHANNEL_ID
            val smallIcon = activeNotif.smallIcon
            val builder = NotificationCompat.Builder(context, channelId)
                .setStyle(style)
                .apply {
                    if (smallIcon != null) {
                        setSmallIcon(IconCompat.createFromIcon(context, smallIcon))
                    } else {
                        setSmallIcon(R.drawable.ic_stat_liveupdate)
                    }
                }
                .setOnlyAlertOnce(true)
                .setAutoCancel(false)
                .setOngoing(false)

            activeNotif.actions?.forEach { action ->
                val actionIcon = action.getIcon()?.let { icon ->
                    try { IconCompat.createFromIcon(context, icon) } catch (_: Throwable) { null }
                }
                val compatAction = NotificationCompat.Action.Builder(
                    actionIcon,
                    action.title,
                    action.actionIntent
                ).apply {
                    action.remoteInputs?.forEach { ri ->
                        addRemoteInput(
                            RemoteInput.Builder(ri.resultKey)
                                .setLabel(ri.label)
                                .build()
                        )
                    }
                }.build()
                builder.addAction(compatAction)
            }

            activeNotif.group?.let { builder.setGroup(it) }
            activeNotif.sortKey?.let { builder.setSortKey(it) }

            val updatedNotification = builder.build()
            notificationManager.notify(mirrorNotificationId, updatedNotification)

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
