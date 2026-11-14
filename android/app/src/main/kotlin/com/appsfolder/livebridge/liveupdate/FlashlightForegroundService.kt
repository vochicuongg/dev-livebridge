package com.kakao.taxi.liveupdate

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraManager
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

class FlashlightForegroundService : Service() {
    private val prefs by lazy { ConverterPrefs(applicationContext) }
    private val controller by lazy { FlashlightController(applicationContext) }
    private val notificationBuilder by lazy {
        FlashlightNotificationBuilder(applicationContext)
    }
    private val notificationManager by lazy {
        NotificationManagerCompat.from(applicationContext)
    }

    private var capability = FlashlightCapability(available = false)
    private var isForegroundActive = false
    private var isTorchCallbackRegistered = false

    /**
     * Round 142: the strength level the torch was last set to. A level
     * tap that repeats the current level must not re-apply the torch:
     * re-asserting the strength makes the LED blink even though nothing
     * changed. -1 means "unknown" (service (re)started, torch changed
     * elsewhere) — the next level tap always applies.
     */
    private var lastAppliedLevelIndex = -1

    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            val activeCameraId = capability.cameraId ?: return
            if (cameraId != activeCameraId) {
                return
            }
            Log.d(TAG, "onTorchModeChanged cameraId=$cameraId enabled=$enabled")
            if (!prefs.getSmartFlashlightEnabled()) {
                stopSelfSafely(clearPreference = false)
                return
            }
            if (!enabled) {
                stopSelfSafely(clearPreference = false)
                return
            }
            refreshNotification()
        }

        override fun onTorchModeUnavailable(cameraId: String) {
            val activeCameraId = capability.cameraId ?: return
            if (cameraId != activeCameraId) {
                return
            }
            Log.d(TAG, "onTorchModeUnavailable cameraId=$cameraId")
            stopSelfSafely(clearPreference = false)
        }

        override fun onTorchStrengthLevelChanged(cameraId: String, newStrengthLevel: Int) {
            val activeCameraId = capability.cameraId ?: return
            if (cameraId != activeCameraId) {
                return
            }
            Log.d(TAG, "onTorchStrengthLevelChanged cameraId=$cameraId level=$newStrengthLevel")
            if (!prefs.getSmartFlashlightEnabled()) {
                stopSelfSafely(clearPreference = false)
                return
            }
            capability = controller.getCapability()
            if (capability.supportsFiveLevels) {
                val resolved = controller.resolveLevelIndexForStrength(
                    capability,
                    newStrengthLevel
                )
                prefs.setSmartFlashlightLevel(resolved)
                // Round 142: the strength changed somewhere else (system
                // Quick Panel, another app). Record it so a later tap on
                // the same level does not re-apply the torch.
                if (resolved != lastAppliedLevelIndex) {
                    lastAppliedLevelIndex = resolved
                }
            }
            refreshNotification()
        }
    }

    override fun onCreate() {
        super.onCreate()
        capability = controller.getCapability()
        ensureChannel(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(
            TAG,
            "onStartCommand action=${intent?.action} " +
                "level=${intent?.getIntExtra(EXTRA_LEVEL_INDEX, -1)}"
        )
        when (intent?.action) {
            ACTION_SET_LEVEL -> {
                prefs.setSmartFlashlightLevel(
                    intent.getIntExtra(EXTRA_LEVEL_INDEX, prefs.getSmartFlashlightLevel())
                )
            }
            ACTION_DISABLE -> {
                runCatching {
                    controller.apply(
                        enabled = false,
                        requestedLevelIndex = prefs.getSmartFlashlightLevel()
                    )
                }.onFailure { error ->
                    Log.e(TAG, "Failed to disable flashlight from notification", error)
                }
                stopSelfSafely(clearPreference = false)
                return START_NOT_STICKY
            }
        }

        if (!prefs.getSmartFlashlightEnabled()) {
            stopSelfSafely(clearPreference = false)
            return START_NOT_STICKY
        }

        val desiredLevel = prefs.getSmartFlashlightLevel()
        val isSetLevel = intent?.action == ACTION_SET_LEVEL
        capability = if (isSetLevel) {
            runCatching {
                // Round 142: only touch the torch when the level really
                // changed. Re-asserting the same strength makes the LED
                // blink for no reason (same-level re-tap, duplicate
                // intent, strength-change echo).
                if (desiredLevel != lastAppliedLevelIndex) {
                    controller.apply(
                        enabled = true,
                        requestedLevelIndex = desiredLevel
                    ).also { lastAppliedLevelIndex = desiredLevel }
                } else {
                    controller.getCapability()
                }
            }.onFailure { error ->
                Log.e(TAG, "Failed to adjust flashlight level", error)
                stopSelfSafely(clearPreference = false)
            }.getOrNull() ?: return START_NOT_STICKY
        } else {
            controller.getCapability()
        }

        Log.d(
            TAG,
            "Flashlight mirror sync available=${capability.available} " +
                "supportsFiveLevels=${capability.supportsFiveLevels} " +
                "level=$desiredLevel action=${intent?.action}"
        )
        if (!capability.available) {
            stopSelfSafely(clearPreference = false)
            return START_NOT_STICKY
        }

        ensureTorchCallbackRegistered()
        // Round 145: plain update again — the Round 142 force-repost
        // (cancel + re-post on every level change) made Samsung tear
        // down and rebuild the Now Bar card, so the pill visibly reset
        // on every brightness change. Plain notify keeps the pill
        // stable, like before Round 142.
        refreshNotification()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        unregisterTorchCallbackIfNeeded()
        hideNotification()
        super.onDestroy()
    }

    /**
     * Round 145: plain notification update, no re-post. Round 142
     * re-posted (cancel + startForeground) on every level change to
     * force Samsung to rebuild the Now Bar card, but that made the pill
     * visibly reset/collapse on every brightness change — worse than
     * the plain update, which keeps the pill stable. The same-level
     * torch guard (lastAppliedLevelIndex) from Round 142 stays: it only
     * stops redundant torch applies.
     */
    private fun refreshNotification() {
        val notification = runCatching {
            notificationBuilder.build(
                prefs = prefs,
                capability = capability
            )
        }.onFailure { error ->
            Log.e(TAG, "Failed to refresh flashlight notification", error)
            stopSelfSafely(clearPreference = false)
        }.getOrNull() ?: return

        runCatching {
            if (isForegroundActive) {
                notificationManager.notify(FlashlightNotificationBuilder.NOTIFICATION_ID, notification)
            } else {
                startForegroundCompat(notification)
                isForegroundActive = true
            }
        }.onFailure { error ->
            Log.e(TAG, "Failed to post flashlight notification", error)
            stopSelfSafely(clearPreference = false)
        }.onSuccess {
            setActiveNotificationVisible(true)
            LiveUpdateNotificationListenerService.requestFlashlightSourceDismissal()
        }
    }

    private fun hideNotification() {
        setActiveNotificationVisible(false)
        if (isForegroundActive) {
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            isForegroundActive = false
        }
        notificationManager.cancel(FlashlightNotificationBuilder.NOTIFICATION_ID)
    }

    private fun stopSelfSafely(clearPreference: Boolean) {
        // Round 142: the torch is going away — forget the applied level
        // so the next enable always applies it.
        lastAppliedLevelIndex = -1
        if (clearPreference) {
            prefs.setSmartFlashlightEnabled(false)
        }
        hideNotification()
        stopSelf()
    }

    private fun startForegroundCompat(notification: Notification) {
        startForeground(
            FlashlightNotificationBuilder.NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        )
    }

    private fun ensureTorchCallbackRegistered() {
        if (isTorchCallbackRegistered) {
            return
        }
        runCatching { controller.registerTorchCallback(torchCallback) }
            .onSuccess {
                isTorchCallbackRegistered = true
                Log.d(TAG, "Torch callback registered")
            }
            .onFailure { error ->
                Log.w(TAG, "Failed to register torch callback", error)
            }
    }

    private fun unregisterTorchCallbackIfNeeded() {
        if (!isTorchCallbackRegistered) {
            return
        }
        runCatching { controller.unregisterTorchCallback(torchCallback) }
            .onFailure { error ->
                Log.w(TAG, "Failed to unregister torch callback", error)
            }
        isTorchCallbackRegistered = false
    }

    companion object {
        const val ACTION_SYNC = "com.kakao.taxi.liveupdate.FLASHLIGHT_SYNC"
        const val ACTION_SET_LEVEL = "com.kakao.taxi.liveupdate.FLASHLIGHT_SET_LEVEL"
        const val ACTION_DISABLE = "com.kakao.taxi.liveupdate.FLASHLIGHT_DISABLE"
        const val EXTRA_LEVEL_INDEX = "level_index"

        private const val TAG = "FlashlightService"

        @Volatile
        private var activeNotificationVisible = false

        fun hasActiveNotification(): Boolean = activeNotificationVisible

        fun sync(context: Context) {
            val prefs = ConverterPrefs(context)
            if (!prefs.getSmartFlashlightEnabled()) {
                stop(context)
                return
            }
            ContextCompat.startForegroundService(
                context,
                Intent(context, FlashlightForegroundService::class.java).apply {
                    action = ACTION_SYNC
                }
            )
        }

        fun stop(context: Context) {
            setActiveNotificationVisible(false)
            context.stopService(Intent(context, FlashlightForegroundService::class.java))
            NotificationManagerCompat.from(context)
                .cancel(FlashlightNotificationBuilder.NOTIFICATION_ID)
        }

        private fun setActiveNotificationVisible(value: Boolean) {
            activeNotificationVisible = value
        }

        private fun ensureChannel(context: Context) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val existing = manager.getNotificationChannel(FlashlightNotificationBuilder.CHANNEL_ID)
            val channelName = FlashlightStrings.channelName(context)
            val channelDescription = FlashlightStrings.channelDescription(context)

            if (existing != null) {
                existing.description = channelDescription
                existing.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                existing.setSound(null, null)
                existing.enableVibration(false)
                manager.createNotificationChannel(existing)
                return
            }

            manager.createNotificationChannel(
                NotificationChannel(
                    FlashlightNotificationBuilder.CHANNEL_ID,
                    channelName,
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = channelDescription
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                    setSound(null, null)
                    enableVibration(false)
                    setShowBadge(false)
                }
            )
        }

    }
}
