package com.kakao.taxi.liveupdate

import android.app.Notification
import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap

/**
 * Stores pending reply state and thread-based lockdown timestamps per conversation thread.
 *
 * ABSOLUTE LOCKDOWN MECHANISM:
 * When the user replies via Wear OS:
 *  - The reply text is saved as a pending reply.
 *  - A [lockdownDeadline] is set based on [threadKey] (packageName + conversationTitle)
 *    so that [LiveUpdateNotifier] completely ignores ALL notification updates from
 *    the target app for the lockdown duration (default 10 seconds).
 *  - This prevents race conditions where apps like Messenger cancel and repost
 *    notifications with new IDs during the "Sending..." state.
 */
object ChatHistoryStore {

    private data class PendingReply(
        val text: String,
        val timestampMs: Long
    )

    private val pendingReplies = ConcurrentHashMap<String, PendingReply>()

    /**
     * ElapsedRealtime timestamp until which the thread is "locked down".
     * Key = threadKey (e.g. "com.facebook.orca_ChatTitle").
     * While locked, [LiveUpdateNotifier] must DROP ALL notification updates
     * (posted, removed, changed) for this conversation thread.
     *
     * This is the ABSOLUTE BLINDFOLD - we ignore the target app completely.
     */
    private val lockdownDeadlines = ConcurrentHashMap<String, Long>()

    /**
     * Caches the currently-displayed [Notification] object per threadKey.
     * Used by the clone-and-inject local echo pattern: instead of rebuilding
     * from scratch (which drops OEM extras and breaks Person identity),
     * we recover the builder from this cached notification and append to it.
     */
    private val activeNotifications = ConcurrentHashMap<String, Notification>()

    /** Max age before a pending reply is auto-expired (30 s). */
    private const val PENDING_REPLY_TTL_MS = 30_000L

    // ---- Pending Reply Management ----

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

    /**
     * Combined helper: Set both lockdown and pending reply text atomically.
     * This is a convenience method used by ReplyInterceptReceiver.
     *
     * @param threadKey Unique thread identifier
     * @param replyText The reply text to store as pending
     * @param durationMs Lockdown duration in milliseconds
     */
    fun setLockdownAndPendingText(threadKey: String, replyText: String, durationMs: Long) {
        setPendingReply(threadKey, replyText)
        setLockdown(threadKey, durationMs)
    }

    // ---- Thread-Based Lockdown (Absolute Blindfold) ----

    /**
     * Activate absolute lockdown for [threadKey] for [durationMs] milliseconds.
     * During lockdown, LiveUpdateNotifier will completely ignore all notification
     * events from the target app for this conversation thread.
     *
     * @param threadKey Unique thread identifier (e.g., "com.facebook.orca_ChatTitle")
     * @param durationMs Lockdown duration in milliseconds (typically 10000 = 10 seconds)
     */
    fun setLockdown(threadKey: String, durationMs: Long) {
        lockdownDeadlines[threadKey] = SystemClock.elapsedRealtime() + durationMs
    }

    /**
     * Returns true if the absolute lockdown is still active for [threadKey].
     * While locked down, LiveUpdateNotifier should return immediately without
     * processing any notification updates for this thread.
     */
    fun isLockedDown(threadKey: String): Boolean {
        val deadline = lockdownDeadlines[threadKey] ?: return false
        if (SystemClock.elapsedRealtime() < deadline) return true
        // Expired – clean up
        lockdownDeadlines.remove(threadKey)
        return false
    }

    /**
     * Check if any thread is locked down for a given package name.
     * This is useful for broad package-level checks.
     */
    fun isAnyThreadLockedForPackage(packageName: String): Boolean {
        val prefix = "${packageName}_"
        val now = SystemClock.elapsedRealtime()
        return lockdownDeadlines.any { (key, deadline) ->
            key.startsWith(prefix) && now < deadline
        }
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
        lockdownDeadlines.clear()
        activeNotifications.clear()
    }
}
