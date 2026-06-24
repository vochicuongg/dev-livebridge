package com.kakao.taxi.liveupdate

import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput

/**
 * Proxy BroadcastReceiver that intercepts reply actions from Wear OS.
 *
 * Requirements:
 * - Append the just-sent reply into the mirrored MessagingStyle history.
 * - Re-notify using the SAME watchNotificationId so Wear OS updates in-place and the card stays.
 * - Copy original actions (including Reply RemoteInputs) to avoid losing the notification.
 * - Always forward the reply to the original app.
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

        val replyText = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(resultKey)
            ?.toString()
            .orEmpty()
            .trim()

        if (replyText.isBlank()) {
            Log.w(TAG, "Empty reply text")
            return
        }

        // 1) Update UI first (best effort). Never block forwarding.
        if (mirrorKey.isNotBlank()) {
            try {
                appendLocalEchoAndRefresh(context, mirrorKey, replyText)
            } catch (t: Throwable) {
                Log.e(TAG, "appendLocalEchoAndRefresh failed for mirrorKey=$mirrorKey", t)
            }
        }

        // 2) Always forward to source app.
        forwardReplyToOriginalApp(context, intent, resultKey, replyText)
    }

    private fun appendLocalEchoAndRefresh(
        context: Context,
        mirrorKey: String,
        replyText: String,
    ) {
        // 1. Lấy thông báo gốc + watch id
        val originalSbn: StatusBarNotification = LiveUpdateNotifier.snapshotForMirrorKey(mirrorKey) ?: run {
            Log.w(TAG, "snapshotForMirrorKey is null: $mirrorKey")
            return
        }

        val watchNotificationId: Int = LiveUpdateNotifier.watchNotificationIdForMirrorKey(mirrorKey) ?: run {
            Log.w(TAG, "watchNotificationIdForMirrorKey is null: $mirrorKey")
            return
        }

        val originalNotification: Notification = originalSbn.notification ?: run {
            Log.w(TAG, "originalNotification is null: $mirrorKey")
            return
        }

        // 2. Cập nhật MessagingStyle
        val messagingStyle = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(originalNotification)
            ?: run {
                Log.w(TAG, "No MessagingStyle in original notification: $mirrorKey")
                return
            }

        val me: Person = LiveUpdateNotifier.LOCAL_USER_ME
        val echoMessage = NotificationCompat.MessagingStyle.Message(
            replyText,
            System.currentTimeMillis(),
            me,
        )
        messagingStyle.addMessage(echoMessage)

        // 3. Build lại notification + copy actions
        val builder = NotificationCompat.Builder(context, originalNotification)
            .setStyle(messagingStyle)
            .setOnlyAlertOnce(true)

        // Copy actions explicitly (Reply RemoteInputs are critical)
        try {
            val actions = originalNotification.actions
            if (actions != null) {
                for (a in actions) {
                    val actionBuilder = NotificationCompat.Action.Builder(
                        a.icon,
                        a.title,
                        a.actionIntent,
                    )

                    val ris = a.remoteInputs
                    if (ris != null) {
                        for (platformRi in ris) {
                            val compatRi = RemoteInput.Builder(platformRi.resultKey)
                                .setLabel(platformRi.label)
                                .setAllowFreeFormInput(platformRi.allowFreeFormInput)
                            val choices = platformRi.choices
                            if (choices != null) {
                                compatRi.setChoices(choices)
                            }
                            actionBuilder.addRemoteInput(compatRi.build())
                        }
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        actionBuilder.setSemanticAction(a.semanticAction)
                    }

                    builder.addAction(actionBuilder.build())
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed copying actions; continuing", t)
        }

        val updated = builder.build().apply {
            flags = flags or Notification.FLAG_ONLY_ALERT_ONCE
        }

        // 4. Re-notify same id
        NotificationManagerCompat.from(context).notify(watchNotificationId, updated)
    }

    private fun forwardReplyToOriginalApp(
        context: Context,
        originalIntent: Intent,
        resultKey: String,
        replyText: String,
    ) {
        try {
            val originalPendingIntent: PendingIntent? = originalIntent.getParcelableExtra(EXTRA_ORIGINAL_PENDING_INTENT)
            if (originalPendingIntent == null) {
                Log.w(TAG, "No original PendingIntent found; cannot forward")
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
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to forward reply", t)
        }
    }
}
