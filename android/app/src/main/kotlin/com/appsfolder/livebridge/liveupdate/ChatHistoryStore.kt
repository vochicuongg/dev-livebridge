package com.kakao.taxi.liveupdate

import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap

/**
 * Stores pending reply state and UI-lock timestamps per conversation thread.
 *
 * When the user replies via Wear OS:
 *  - The reply text is saved as a pending reply.
 *  - A [uiLockTimestamp] is set so that [LiveUpdateNotifier] drops any
 *    state-downgrade notifications (missing MessagingStyle, "Sending…" text,
 *    or outright removal) for [UI_LOCK_DURATION_MS].
 */
object ChatHistoryStore {

    private data class PendingReply(
        val text: String,
        val timestampMs: Long
    )

    private val pendingReplies = ConcurrentHashMap<String, PendingReply>()

    /**
     * ElapsedRealtime timestamp until which the UI is "locked" for a thread.
     * Key = threadKey (e.g. "com.facebook.orca_ChatTitle").
     * While locked, [LiveUpdateNotifier] must DROP any notification update
     * that lacks a valid MessagingStyle (the "Sending…" downgrade) and
     * IGNORE notification removals.
     */
    private val uiLockTimestamps = ConcurrentHashMap<String, Long>()

    /** Max age before a pending reply is auto-expired (30 s). */
    private const val PENDING_REPLY_TTL_MS = 30_000L

    /** Duration of the UI lock after a reply (6 s). */
    private const val UI_LOCK_DURATION_MS = 6_000L

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

    // ---- UI Lock ----

    /** Activate the UI lock for [threadKey]. */
    fun setUiLock(threadKey: String) {
        uiLockTimestamps[threadKey] = SystemClock.elapsedRealtime() + UI_LOCK_DURATION_MS
    }

    /** Returns true if the UI lock is still active for [threadKey]. */
    fun isUiLocked(threadKey: String): Boolean {
        val deadline = uiLockTimestamps[threadKey] ?: return false
        if (SystemClock.elapsedRealtime() < deadline) return true
        // Expired – clean up
        uiLockTimestamps.remove(threadKey)
        return false
    }

    /** Check UI lock by mirrorKey: scans all threadKeys that end with the conversation suffix. */
    fun isAnyThreadLockedForPackage(packageName: String): Boolean {
        val prefix = "${packageName}_"
        val now = SystemClock.elapsedRealtime()
        return uiLockTimestamps.any { (key, deadline) ->
            key.startsWith(prefix) && now < deadline
        }
    }

    fun clear() {
        pendingReplies.clear()
        uiLockTimestamps.clear()
    }
}
