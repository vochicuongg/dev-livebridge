package com.kakao.taxi.liveupdate

import java.util.concurrent.ConcurrentHashMap

/**
 * Stores pending reply state per conversation thread.
 *
 * When the user replies via Wear OS, the reply text is saved here.
 * On the next `onNotificationPosted`, [LiveUpdateNotifier] checks this store:
 *  - If the target app's MessagingStyle does NOT yet contain the text,
 *    inject it as a synthetic "Me" message (right-aligned blue bubble).
 *  - If the target app DOES contain the text (reply confirmed), clear it.
 */
object ChatHistoryStore {

    private data class PendingReply(
        val text: String,
        val timestampMs: Long
    )

    private val pendingReplies = ConcurrentHashMap<String, PendingReply>()

    /** Max age before a pending reply is auto-expired (30 s). */
    private const val PENDING_REPLY_TTL_MS = 30_000L

    fun setPendingReply(threadKey: String, text: String) {
        pendingReplies[threadKey] = PendingReply(text.trim(), System.currentTimeMillis())
    }

    /** Returns the pending reply text if one exists and hasn't expired, else null. */
    fun getPendingReplyText(threadKey: String): String? {
        val pending = pendingReplies[threadKey] ?: return null
        if (System.currentTimeMillis() - pending.timestampMs > PENDING_REPLY_TTL_MS) {
            pendingReplies.remove(threadKey)
            return null
        }
        return pending.text
    }

    fun clearPendingReply(threadKey: String) {
        pendingReplies.remove(threadKey)
    }

    fun clear() {
        pendingReplies.clear()
    }
}
