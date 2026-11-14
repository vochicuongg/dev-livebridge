package com.kakao.taxi.liveupdate

import android.content.Context
import android.util.Log

/**
 * Disk-backed record of inline replies that have been received (from the
 * watch or the phone editor) but not yet confirmed as forwarded to the
 * source app.
 *
 * Why this exists: [ChatHistoryStore] is RAM-only. Before round 137 a
 * Wear OS reply lived only in the broadcast [android.content.Intent] and
 * in that in-memory store while [ReplyInterceptReceiver] waited 500 ms
 * before forwarding it. If the process was killed, Doze froze the
 * [android.os.Handler] delay, or the broadcast window expired in that
 * window, the text was gone with no trace and no retry — the user saw
 * the local-echo bubble and believed the message was sent.
 *
 * The store is written with [android.content.SharedPreferences.Editor.commit]
 * (synchronous) BEFORE the forward attempt, so a received reply survives
 * a process death at least as a record. It is marked sent/failed right
 * after the forward attempt.
 *
 * What it deliberately does NOT do: re-send on its own. The source app's
 * [android.app.PendingIntent] cannot be persisted across a process death,
 * so there is no safe automatic resend without a fresh PendingIntent
 * from a new source notification. A later round can surface failed
 * entries (see [getPending]) as a "reply failed — resend?" prompt; until
 * then the record exists for logcat/forensics and for the in-receiver
 * retry loop to consult.
 */
object PendingReplyStore {

    private const val TAG = "PendingReplyStore"
    private const val PREFS_NAME = "livebridge_pending_replies"

    const val STATUS_PENDING = "pending"
    const val STATUS_SENT = "sent"
    const val STATUS_FAILED = "failed"

    data class PendingReply(
        val threadKey: String,
        val mirrorKey: String,
        val sourceKey: String,
        val mirrorNotificationId: Int,
        val resultKey: String,
        val text: String,
        val receivedAtMs: Long,
        val status: String
    )

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun key(threadKey: String, field: String) = "reply:$threadKey:$field"

    /**
     * Persists a received reply synchronously. Returns false only if the
     * disk write itself failed; callers must still attempt the forward.
     */
    fun savePending(
        context: Context,
        threadKey: String,
        mirrorKey: String,
        sourceKey: String,
        mirrorNotificationId: Int,
        resultKey: String,
        text: String
    ): Boolean {
        if (threadKey.isBlank() || text.isBlank()) return false
        return try {
            val committed = prefs(context).edit()
                .putString(key(threadKey, "mirrorKey"), mirrorKey)
                .putString(key(threadKey, "sourceKey"), sourceKey)
                .putInt(key(threadKey, "mirrorNotificationId"), mirrorNotificationId)
                .putString(key(threadKey, "resultKey"), resultKey)
                .putString(key(threadKey, "text"), text)
                .putLong(key(threadKey, "receivedAtMs"), System.currentTimeMillis())
                .putString(key(threadKey, "status"), STATUS_PENDING)
                .commit()
            if (!committed) {
                Log.w(TAG, "savePending commit() returned false for threadKey=$threadKey")
            }
            committed
        } catch (error: Throwable) {
            Log.e(TAG, "savePending failed for threadKey=$threadKey", error)
            false
        }
    }

    fun markSent(context: Context, threadKey: String) {
        if (threadKey.isBlank()) return
        prefs(context).edit().putString(key(threadKey, "status"), STATUS_SENT).apply()
    }

    fun markFailed(context: Context, threadKey: String) {
        if (threadKey.isBlank()) return
        prefs(context).edit().putString(key(threadKey, "status"), STATUS_FAILED).apply()
    }

    fun getPending(context: Context, threadKey: String): PendingReply? {
        if (threadKey.isBlank()) return null
        val prefs = prefs(context)
        val text = prefs.getString(key(threadKey, "text"), null) ?: return null
        return PendingReply(
            threadKey = threadKey,
            mirrorKey = prefs.getString(key(threadKey, "mirrorKey"), "").orEmpty(),
            sourceKey = prefs.getString(key(threadKey, "sourceKey"), "").orEmpty(),
            mirrorNotificationId = prefs.getInt(key(threadKey, "mirrorNotificationId"), Int.MIN_VALUE),
            resultKey = prefs.getString(key(threadKey, "resultKey"), "").orEmpty(),
            text = text,
            receivedAtMs = prefs.getLong(key(threadKey, "receivedAtMs"), 0L),
            status = prefs.getString(key(threadKey, "status"), STATUS_PENDING) ?: STATUS_PENDING
        )
    }

    fun clear(context: Context, threadKey: String) {
        if (threadKey.isBlank()) return
        prefs(context).edit()
            .remove(key(threadKey, "mirrorKey"))
            .remove(key(threadKey, "sourceKey"))
            .remove(key(threadKey, "mirrorNotificationId"))
            .remove(key(threadKey, "resultKey"))
            .remove(key(threadKey, "text"))
            .remove(key(threadKey, "receivedAtMs"))
            .remove(key(threadKey, "status"))
            .apply()
    }
}
