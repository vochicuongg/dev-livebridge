package com.kakao.taxi.liveupdate

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.kakao.taxi.MainActivity
import com.kakao.taxi.R

/**
 * A foreground service that keeps the LiveBridge process alive and runs a
 * periodic watchdog to detect and recover from silent
 * [LiveUpdateNotificationListenerService] unbinds caused by Samsung One UI
 * (or other OEM) aggressive battery management.
 *
 * ### Watchdog behaviour
 * Every [WATCHDOG_INTERVAL_MS] (default 15 s) the watchdog checks whether
 * the listener's heartbeat timestamp is stale (older than
 * [WATCHDOG_HEARTBEAT_STALE_MS]).  If so, it calls
 * [NotificationListenerService.requestRebind] to force the OS to reconnect
 * the service, with exponential back-off up to [MAX_WATCHDOG_REBIND_ATTEMPTS].
 */
class KeepAliveForegroundService : Service() {

    private val watchdogHandler = Handler(Looper.getMainLooper())
    private var watchdogRebindAttempts = 0

    private val watchdogRunnable = object : Runnable {
        override fun run() {
            performWatchdogCheck()
            watchdogHandler.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    // ── Lifecycle ────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (DeviceBlocker.isBlockedDevice()) {
            stopWatchdog()
            stopSelf()
            return START_NOT_STICKY
        }
        val notification = buildNotification()
        startForegroundCompat(notification)
        startWatchdog()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopWatchdog()
        super.onDestroy()
    }

    // ── Watchdog ─────────────────────────────────────────────────────

    private fun startWatchdog() {
        watchdogHandler.removeCallbacks(watchdogRunnable)
        watchdogRebindAttempts = 0
        watchdogHandler.postDelayed(watchdogRunnable, WATCHDOG_INTERVAL_MS)
        Log.i(TAG, "Listener watchdog started (interval=${WATCHDOG_INTERVAL_MS}ms)")
    }

    private fun stopWatchdog() {
        watchdogHandler.removeCallbacks(watchdogRunnable)
        Log.i(TAG, "Listener watchdog stopped")
    }

    /**
     * Core watchdog tick.  Checks whether the notification listener is
     * still alive by inspecting its heartbeat timestamp.  If stale,
     * requests a rebind with exponential back-off.
     */
    private fun performWatchdogCheck() {
        // Only act if the listener permission is actually granted.
        if (!isListenerEnabled()) {
            Log.v(TAG, "Watchdog: listener permission not granted, skipping")
            watchdogRebindAttempts = 0
            return
        }

        if (LiveUpdateNotificationListenerService.isListenerAlive(WATCHDOG_HEARTBEAT_STALE_MS)) {
            // Listener is healthy — reset back-off counter.
            if (watchdogRebindAttempts > 0) {
                Log.i(TAG, "Watchdog: listener recovered after $watchdogRebindAttempts rebind attempt(s)")
            }
            watchdogRebindAttempts = 0
            return
        }

        // Listener is stale or dead.
        if (watchdogRebindAttempts >= MAX_WATCHDOG_REBIND_ATTEMPTS) {
            Log.w(
                TAG,
                "Watchdog: listener still dead after $MAX_WATCHDOG_REBIND_ATTEMPTS rebind attempts, " +
                    "will keep retrying at interval"
            )
            // Reset so we keep trying periodically instead of giving up forever.
            watchdogRebindAttempts = 0
        }

        watchdogRebindAttempts++
        Log.w(
            TAG,
            "Watchdog: listener heartbeat stale — requesting rebind " +
                "(attempt $watchdogRebindAttempts/$MAX_WATCHDOG_REBIND_ATTEMPTS)"
        )
        requestListenerRebind()
    }

    /**
     * Programmatically forces the system to reconnect the
     * [LiveUpdateNotificationListenerService] via [NotificationListenerService.requestRebind].
     */
    private fun requestListenerRebind() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return
        }
        try {
            NotificationListenerService.requestRebind(
                ComponentName(this, LiveUpdateNotificationListenerService::class.java)
            )
            Log.i(TAG, "Watchdog: requestRebind dispatched")
        } catch (error: Throwable) {
            Log.e(TAG, "Watchdog: requestRebind failed", error)
        }
    }

    private fun isListenerEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver,
            "enabled_notification_listeners"
        ) ?: return false
        val target = ComponentName(this, LiveUpdateNotificationListenerService::class.java)
        return enabled.split(":")
            .mapNotNull(ComponentName::unflattenFromString)
            .any { it == target }
    }

    // ── Foreground notification ──────────────────────────────────────

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_liveupdate)
            .setContentTitle("background mode")
            .setContentText("Keep this notification here for LiveBridge stability")
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val TAG = "KeepAliveService"
        private const val CHANNEL_ID = "livebridge_keep_alive"
        private const val CHANNEL_NAME = "Background Mode"
        private const val NOTIFICATION_ID = 41130

        /** How often the watchdog checks the listener heartbeat. */
        private const val WATCHDOG_INTERVAL_MS = 15_000L

        /**
         * If the listener's last heartbeat is older than this threshold
         * the watchdog considers it dead and triggers a rebind.
         * Must be comfortably larger than [LiveUpdateNotificationListenerService.SNAPSHOT_SYNC_INTERVAL_MS]
         * (currently 4 s) to avoid false positives.
         */
        private const val WATCHDOG_HEARTBEAT_STALE_MS = 30_000L

        /** Maximum consecutive rebind attempts before the counter resets. */
        private const val MAX_WATCHDOG_REBIND_ATTEMPTS = 5

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, KeepAliveForegroundService::class.java)
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, KeepAliveForegroundService::class.java))
        }

        // ── Battery optimization helpers ─────────────────────────────

        /**
         * Returns `true` if this app is already exempt from battery
         * optimizations (i.e. is on the "unrestricted" list).
         */
        fun isBatteryOptimizationExempt(context: Context): Boolean {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                ?: return false
            return pm.isIgnoringBatteryOptimizations(context.packageName)
        }

        /**
         * Builds an [Intent] that asks the user to exempt this app from
         * battery optimizations via the system dialog.
         *
         * Returns `null` on devices where the intent cannot be resolved
         * or if the app is already exempt.
         */
        fun buildBatteryOptimizationExemptionIntent(context: Context): Intent? {
            if (isBatteryOptimizationExempt(context)) {
                return null
            }
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
            return if (intent.resolveActivity(context.packageManager) != null) intent else null
        }

        private fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                return
            }
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val existing = manager.getNotificationChannel(CHANNEL_ID)
            if (existing != null) {
                return
            }
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Keep this notification here to use LiveBridge"
                    lockscreenVisibility = Notification.VISIBILITY_SECRET
                }
            )
        }
    }
}
