package com.kakao.taxi.liveupdate

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.core.app.RemoteInput

/**
 * BroadcastReceiver that receives inline reply and starts ReplyProxyService.
 * This is needed because Wear OS doesn't allow starting Service directly from RemoteInput.
 */
class ReplyProxyReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ReplyProxyReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "=== ReplyProxyReceiver.onReceive CALLED ===")
        Log.d(TAG, "action=${intent.action}")
        Log.d(TAG, "extras=${intent.extras}")
        
        // Extract RemoteInput results from the received intent
        val remoteInputResults = RemoteInput.getResultsFromIntent(intent)
        Log.d(TAG, "remoteInputResults in Receiver: $remoteInputResults")
        
        if (remoteInputResults != null) {
            Log.d(TAG, "RemoteInput keys: ${remoteInputResults.keySet()}")
            remoteInputResults.keySet().forEach { key ->
                Log.d(TAG, "  RemoteInput key='$key' -> value='${remoteInputResults.getCharSequence(key)}'")
            }
        }
        
        // Forward the intent to ReplyProxyService
        val serviceIntent = Intent(context, ReplyProxyService::class.java).apply {
            action = intent.action
            putExtras(intent.extras ?: run {
                Log.e(TAG, "No extras in intent, returning")
                return
            })
            
            // CRITICAL: Manually copy RemoteInput results to the service intent
            // because putExtras() doesn't preserve RemoteInput results
            if (remoteInputResults != null) {
                RemoteInput.addResultsToIntent(
                    arrayOf(RemoteInput.Builder("direct_reply").build()),
                    this,
                    remoteInputResults
                )
                Log.d(TAG, "RemoteInput results manually added to service intent")
            }
        }
        
        try {
            context.startService(serviceIntent)
            Log.d(TAG, "ReplyProxyService started successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start ReplyProxyService", e)
        }
    }
}
