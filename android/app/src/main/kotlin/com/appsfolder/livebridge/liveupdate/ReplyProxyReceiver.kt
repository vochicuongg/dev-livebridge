package com.kakao.taxi.liveupdate

import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log

/**
 * BroadcastReceiver that receives inline reply and starts ReplyProxyService.
 * This is needed because Wear OS doesn't allow starting Service directly from RemoteInput.
 *
 * Some surfaces (notably the Samsung Now Bar) fire the reply action without
 * showing a RemoteInput editor, so the broadcast arrives with no reply text.
 * In that case ReplyInputActivity is opened so the user can type the reply;
 * it forwards the typed text to ReplyProxyService like a normal reply.
 */
class ReplyProxyReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ReplyProxyReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "onReceive called, action=${intent.action}")

        val extras: Bundle = intent.extras ?: return
        val resultKey = intent.getStringExtra(ReplyProxyService.EXTRA_RESULT_KEY).orEmpty()
        val replyText = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(resultKey)
            ?.toString()
            ?.trim()

        if (!replyText.isNullOrBlank()) {
            forwardToService(context, extras)
            return
        }

        // No reply text: the host surface showed no editor. Open ours.
        val activityIntent = Intent(context, ReplyInputActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtras(extras)
        }
        try {
            context.startActivity(activityIntent)
            Log.d(TAG, "ReplyInputActivity started (host provided no reply UI)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start ReplyInputActivity, forwarding to service", e)
            forwardToService(context, extras)
        }
    }

    private fun forwardToService(context: Context, extras: Bundle) {
        val serviceIntent = Intent(context, ReplyProxyService::class.java).apply {
            action = ReplyProxyService.ACTION_PROXY_REPLY
            putExtras(extras)
        }

        try {
            context.startService(serviceIntent)
            Log.d(TAG, "ReplyProxyService started successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start ReplyProxyService", e)
        }
    }
}
