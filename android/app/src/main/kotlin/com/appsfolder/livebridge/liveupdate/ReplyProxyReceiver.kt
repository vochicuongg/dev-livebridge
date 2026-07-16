package com.kakao.taxi.liveupdate

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * BroadcastReceiver that receives inline reply and starts ReplyProxyService.
 * This is needed because Wear OS doesn't allow starting Service directly from RemoteInput.
 */
class ReplyProxyReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ReplyProxyReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "onReceive called, action=${intent.action}")
        
        // Forward the intent to ReplyProxyService
        val serviceIntent = Intent(context, ReplyProxyService::class.java).apply {
            action = intent.action
            putExtras(intent.extras ?: return)
        }
        
        try {
            context.startService(serviceIntent)
            Log.d(TAG, "ReplyProxyService started successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start ReplyProxyService", e)
        }
    }
}
