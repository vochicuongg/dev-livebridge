package com.kakao.taxi.liveupdate

import android.app.Notification
import java.util.concurrent.ConcurrentHashMap

/**
 * Stores pending reply state and mirrored chat history per conversation thread.
 */
object ChatHistoryStore {
    data class ChatMessageSnapshot(
        val text: CharSequence,
        val timestampMs: Long,
        val senderName: String?,
        val senderKey: String?,
        val isMe: Boolean
    )

    private data class PendingReply(
        val text: String,
        val timestampMs: Long
    )

    private val pendingReplies = ConcurrentHashMap<String, PendingReply>()
    private val messageHistory = ConcurrentHashMap<String, MutableList<ChatMessageSnapshot>>()

    /**
     * Caches the currently-displayed [Notification] object per threadKey.
     * Used by the clone-and-inject local echo pattern: instead of rebuilding
     * from scratch (which drops OEM extras and breaks Person identity),
     * we recover the builder from this cached notification and append to it.
     */
    private val activeNotifications = ConcurrentHashMap<String, Notification>()

    /** Max age before a pending reply is auto-expired (30 s). */
    private const val PENDING_REPLY_TTL_MS = 30_000L
    private const val MAX_MESSAGES_PER_THREAD = 10

    // ---- Pending Reply Management ----

    fun setPendingReply(threadKey: String, text: String) {
        pendingReplies[threadKey] = PendingReply(text.trim(), System.currentTimeMillis())
    }

    fun upsertSourceMessages(threadKey: String, messages: List<ChatMessageSnapshot>) {
        if (threadKey.isBlank() || messages.isEmpty()) {
            return
        }

        val history = messageHistory.getOrPut(threadKey) { mutableListOf() }
        messages.forEach { message ->
            val normalizedText = message.text.toString().trim()
            if (normalizedText.isBlank()) {
                return@forEach
            }

            val alreadyPresent = history.any { existing ->
                existing.text.toString() == normalizedText &&
                    kotlin.math.abs(existing.timestampMs - message.timestampMs) < 5_000L &&
                    existing.isMe == message.isMe
            }
            if (!alreadyPresent) {
                history.add(message.copy(text = normalizedText))
            }
        }

        history.sortBy { it.timestampMs }
        if (history.size > MAX_MESSAGES_PER_THREAD) {
            messageHistory[threadKey] = history.takeLast(MAX_MESSAGES_PER_THREAD).toMutableList()
        }
    }

    fun appendLocalReply(threadKey: String, text: String, timestampMs: Long = System.currentTimeMillis()) {
        if (threadKey.isBlank() || text.isBlank()) {
            return
        }

        val history = messageHistory.getOrPut(threadKey) { mutableListOf() }
        val normalizedText = text.trim()
        val alreadyPresent = history.any { existing ->
            existing.isMe &&
                existing.text.toString() == normalizedText &&
                kotlin.math.abs(existing.timestampMs - timestampMs) < 5_000L
        }
        if (!alreadyPresent) {
            history.add(
                ChatMessageSnapshot(
                    text = normalizedText,
                    timestampMs = timestampMs,
                    senderName = null,
                    senderKey = null,
                    isMe = true
                )
            )
        }

        history.sortBy { it.timestampMs }
        if (history.size > MAX_MESSAGES_PER_THREAD) {
            messageHistory[threadKey] = history.takeLast(MAX_MESSAGES_PER_THREAD).toMutableList()
        }
    }

    fun getMessages(threadKey: String): List<ChatMessageSnapshot> {
        return messageHistory[threadKey]
            ?.sortedBy { it.timestampMs }
            ?.takeLast(MAX_MESSAGES_PER_THREAD)
            .orEmpty()
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

    // ---- Active Notification Cache (Clone-and-Inject) ----

    /** Cache the currently-displayed notification for a thread so the local echo
     *  can clone it instead of rebuilding from scratch. */
    fun setActiveNotification(threadKey: String, notification: Notification) {
        activeNotifications[threadKey] = notification
    }

    /** Retrieve the cached notification for clone-and-inject local echo. */
    fun getActiveNotification(threadKey: String): Notification? =
        activeNotifications[threadKey]

    fun clearActiveNotification(threadKey: String) {
        activeNotifications.remove(threadKey)
    }

    fun clear() {
        pendingReplies.clear()
        messageHistory.clear()
        activeNotifications.clear()
    }
}
