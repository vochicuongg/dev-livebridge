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
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.graphics.drawable.IconCompat
import com.kakao.taxi.R

/**
 * Proxy BroadcastReceiver that intercepts Wear OS inline replies.
 *
 * Flow (round 137 — Wear OS reply loss fix):
 * 1. Extract typed RemoteInput text.
 * 2. Persist it to [PendingReplyStore] (synchronous disk write) BEFORE
 *    anything else can lose it. [ChatHistoryStore] alone is RAM-only.
 * 3. Store it as a local reply in ChatHistoryStore (it will be merged into
 *    the mirrored chat history when the other party replies later).
 * 4. Immediately re-post the mirrored MessagingStyle notification with the
 *    local echo injected (clone-and-inject). Gboard on Wear OS only renders
 *    the "local echo" bubble when the app calls NotificationManager.notify()
 *    on the existing notification after the reply is cached; Samsung/One UI
 *    Watch fakes this UI on its own, Gboard does not.
 * 5. Forward the original PendingIntent IMMEDIATELY (no 500 ms delay) under
 *    a PARTIAL_WAKE_LOCK, with up to [FORWARD_MAX_ATTEMPTS] attempts. The
 *    old 500 ms Handler delay was the loss window: if the watch screen
 *    went off / the phone dozed / the process died inside it, the reply
 *    was never forwarded, yet step 4 had already shown a "sent" bubble.
 *    The mirror/source dismissal still waits [DISMISS_DELAY_MS] after a
 *    successful forward, and only then; on a failed forward the mirror
 *    is left up so the user can retry instead of believing it sent.
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

        /**
         * Forward attempts for the original PendingIntent. The forward runs
         * immediately (round 137 removed the old 500 ms pre-send delay);
         * a transient failure is retried after [FORWARD_RETRY_DELAY_MS].
         * A cancelled PendingIntent is never retried.
         */
        private const val FORWARD_MAX_ATTEMPTS = 3
        private const val FORWARD_RETRY_DELAY_MS = 250L

        /** WakeLock ceiling for the whole receive → forward → dismiss flow. */
        private const val WAKE_LOCK_TIMEOUT_MS = 10_000L

        /**
         * Delay between forwarding the reply to the source app and cancelling
         * the mirror/source notifications. Keeps the re-posted local-echo UI
         * on screen long enough for Gboard to render it and guarantees the
         * cancel always runs AFTER the notify() from the local-echo update.
         * Must stay within goAsync()'s ~10 s window (forward is immediate
         * now, so ~1500 ms total).
         */
        private const val DISMISS_DELAY_MS = 1_500L
        private const val INVALID_NOTIFICATION_ID = Int.MIN_VALUE
    }

    override fun onReceive(context: Context, intent: Intent?) {
        Log.d(TAG, "onReceive called, action=${intent?.action}")
        if (intent == null || intent.action != ACTION_PROXY_REPLY) {
            Log.w(TAG, "Intent null or wrong action, returning")
            return
        }

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

        Log.d(TAG, "Intercepted reply for threadKey=$threadKey, mirrorKey=$mirrorKey")

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

        // Round 137: persist the reply to disk BEFORE the echo/forward work,
        // so a received reply survives a process death at least as a record.
        PendingReplyStore.savePending(
            context = context.applicationContext,
            threadKey = threadKey,
            mirrorKey = mirrorKey,
            sourceKey = sourceKey,
            mirrorNotificationId = mirrorNotificationId,
            resultKey = resultKey,
            text = replyText
        )

        // goAsync() up front (not after the echo work) and a WakeLock for
        // the whole flow, so Doze / screen-off cannot freeze the delayed
        // dismiss or kill the broadcast before the forward runs.
        val pendingResult = goAsync()
        val wakeLock = acquireWakeLock(context.applicationContext)
        var flowFinished = false
        fun finishFlow() {
            if (flowFinished) return
            flowFinished = true
            runCatching {
                if (wakeLock?.isHeld == true) wakeLock.release()
            }
            pendingResult.finish()
        }

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

        val mainHandler = Handler(Looper.getMainLooper())
        val forwardIntent = Intent()
        val resultBundle = Bundle().apply {
            putCharSequence(resultKey, replyText)
        }
        RemoteInput.addResultsToIntent(
            arrayOf(RemoteInput.Builder(resultKey).build()),
            forwardIntent,
            resultBundle
        )

        // Forward IMMEDIATELY — no pre-send delay (round 137). The old
        // 500 ms wait was the loss window: the reply sat un-forwarded in
        // RAM while the watch screen could go off / the phone could doze.
        // Retries are scheduled on the main Handler so onReceive itself
        // never blocks. The mirror/source dismissal still waits
        // DISMISS_DELAY_MS after a successful forward, and only then; a
        // failed forward leaves the mirror up so the user can retry
        // instead of believing the message was sent.
        fun attemptForward(attempt: Int) {
            try {
                originalPendingIntent.send(context, 0, forwardIntent)
                Log.d(TAG, "Reply forwarded to source app (attempt $attempt)")
                PendingReplyStore.markSent(context.applicationContext, threadKey)
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
                        finishFlow()
                    }
                }, DISMISS_DELAY_MS)
            } catch (e: PendingIntent.CanceledException) {
                Log.e(TAG, "Original PendingIntent was cancelled; reply not sent.", e)
                PendingReplyStore.markFailed(context.applicationContext, threadKey)
                finishFlow()
            } catch (e: Exception) {
                if (attempt < FORWARD_MAX_ATTEMPTS) {
                    Log.w(
                        TAG,
                        "Forward attempt $attempt failed, retrying in ${FORWARD_RETRY_DELAY_MS}ms",
                        e
                    )
                    mainHandler.postDelayed(
                        { attemptForward(attempt + 1) },
                        FORWARD_RETRY_DELAY_MS
                    )
                } else {
                    Log.e(
                        TAG,
                        "Failed to forward reply after $FORWARD_MAX_ATTEMPTS attempts; mirror left up so the user can retry.",
                        e
                    )
                    PendingReplyStore.markFailed(context.applicationContext, threadKey)
                    finishFlow()
                }
            }
        }
        mainHandler.post { attemptForward(1) }
    }

    /**
     * Holds a PARTIAL_WAKE_LOCK for the receive → forward → dismiss flow
     * so Doze / screen-off cannot freeze the Handler delays mid-reply.
     * The lock has a hard timeout ([WAKE_LOCK_TIMEOUT_MS]) so the system
     * releases it even if this receiver crashes before [finishFlow].
     */
    private fun acquireWakeLock(context: Context): PowerManager.WakeLock? {
        return try {
            val powerManager =
                context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                    ?: return null
            powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "livebridge:WearReply"
            ).apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
        } catch (error: Throwable) {
            Log.w(TAG, "Could not acquire WakeLock; continuing without it.", error)
            null
        }
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

            // 5. Build a new NotificationCompat.Builder from the channel ID
            //    of the active notification (no recoverBuilder needed).
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

            // Preserve existing actions (e.g. reply action) from the original
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

            // Preserve group and sort keys
            activeNotif.group?.let { builder.setGroup(it) }
            activeNotif.sortKey?.let { builder.setSortKey(it) }

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
