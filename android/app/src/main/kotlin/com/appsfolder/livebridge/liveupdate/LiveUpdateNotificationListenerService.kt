package com.kakao.taxi.liveupdate

import android.app.Notification
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.NotificationListenerService.RankingMap
import android.service.notification.StatusBarNotification
import android.util.Log
import kotlin.math.min

class LiveUpdateNotificationListenerService : NotificationListenerService() {
    private val prefs by lazy { ConverterPrefs(applicationContext) }
    private val flashlightController by lazy { FlashlightController(applicationContext) }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val processingHandler = NotificationProcessing.handler
    private val refreshPolicy = SnapshotRefreshPolicy()
    private val sourceLifecycle = NotificationProcessing.sourceLifecycle
    private val originalRemovalPolicy = NotificationProcessing.originalRemovalPolicy
    private var lastSettings: Map<String, *>? = null
    private var lastInterruptionFilter: Int? = null
    @Volatile
    private var destroyed = false
    private val selfDismissLock = Any()
    private val selfDismissedSourcesByKey = mutableMapOf<String, ProtectedSourceDismissal>()
    private val selfDismissedSourceKeys = mutableSetOf<String>()
    private val protectedMirrorDismissalsById = mutableMapOf<Int, ProtectedSourceDismissal>()
    private val selfDismissedFlashlightSourceKeys = mutableSetOf<String>()
    private var trackedFlashlightSourceKey: String? = null
    private val progressDismissedSourceKeys = mutableSetOf<String>()
    private val progressAbsentStreaks = mutableMapOf<String, Int>()
    private val progressFingerprints = mutableMapOf<String, Pair<String, Long>>()
    private val progressContentFingerprints = mutableMapOf<String, String>()
    private val progressTapContentBaselines = mutableMapOf<String, String>()
    private val staleProgressFingerprints = mutableMapOf<String, String>()
    private val hardRetiredProgressContents = mutableMapOf<String, String>()
    private val progressProbation = mutableMapOf<String, Pair<String, Long>>()
    private var isTorchCallbackRegistered = false
    private var rebindAttempts = 0
    private var rebindScheduled = false
    private var snapshotSyncScheduled = false
    private var lockscreenStateReceiverRegistered = false
    private var chargingInfoReceiverRegistered = false

    private val lockscreenPrivacyRefreshRunnable = Runnable {
        requestImmediateSnapshotSync()
    }

    private val lockscreenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF,
                Intent.ACTION_SCREEN_ON,
                Intent.ACTION_USER_PRESENT -> handleLockscreenStateChanged(intent.action)
            }
        }
    }

    private val chargingInfoReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            handleChargingInfoBatteryChanged(intent)
        }
    }

    private var deviceConnectionReceiverRegistered = false
    private var connectedDevices: List<ConnectedDeviceInfo> = emptyList()

    private val deviceConnectionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // The broadcast is only a trigger: the scan re-reads the
            // stack's own state, which also settles profile-specific
            // events this receiver does not subscribe to.
            syncConnectedDevicesMonitoring()
        }
    }

    private data class ProtectedSourceDismissal(
        val sourceKey: String,
        val sourcePackageName: String,
        val sourceId: Int,
        val sourceTag: String?,
        val sourceGroupKey: String?,
        val sourceSbn: StatusBarNotification,
        val mirrorNotificationId: Int,
        val mirrorKey: String?,
        val expiresAtMs: Long,
        var sourceRemovalConsumed: Boolean = false,
        var mirrorRepostAttempts: Int = 0
    ) {
        fun matchesSource(sbn: StatusBarNotification): Boolean {
            if (sourceKey == sbn.key) {
                return true
            }
            if (sourcePackageName == sbn.packageName &&
                sourceId == sbn.id &&
                sourceTag == sbn.tag
            ) {
                return true
            }
            return sourcePackageName == sbn.packageName &&
                !sourceGroupKey.isNullOrBlank() &&
                sourceGroupKey == sbn.groupKey &&
                sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0
        }
    }

    private data class ReplySourceCancelTarget(
        val sourceKey: String,
        val expiresAtMs: Long
    )

    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            val flashlightCameraId = flashlightController.getCapability().cameraId ?: return
            if (cameraId != flashlightCameraId) {
                return
            }
            Log.d(TAG, "Listener torch mode changed: cameraId=$cameraId enabled=$enabled")
            handleObservedTorchState(enabled)
        }

        override fun onTorchModeUnavailable(cameraId: String) {
            val flashlightCameraId = flashlightController.getCapability().cameraId ?: return
            if (cameraId != flashlightCameraId) {
                return
            }
            Log.d(TAG, "Listener torch unavailable: cameraId=$cameraId")
            handleObservedTorchUnavailable()
        }
    }

    @Volatile
    private var listenerConnected = false

    private val rebindRunnable = object : Runnable {
        override fun run() {
            rebindScheduled = false
            if (listenerConnected || !isListenerEnabled(applicationContext)) {
                return
            }
            requestRebindIfEnabled(applicationContext, "listener_disconnected")
            rebindAttempts++
            scheduleRebind("retry")
        }
    }

    private val snapshotSyncRunnable = object : Runnable {
        override fun run() {
            snapshotSyncScheduled = false
            if (!listenerConnected) {
                return
            }
            processingHandler.post {
                if (destroyed || !listenerConnected) return@post
                try {
                    val settings = prefs.runtimeSettingsSnapshot()
                    val filter = (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                        .currentInterruptionFilter
                    if (settings != lastSettings || filter != lastInterruptionFilter) {
                        refreshPolicy.invalidate()
                        lastSettings = settings
                        lastInterruptionFilter = filter
                    }
                    if (!prefs.getConverterEnabled()) {
                        refreshPolicy.invalidate()
                        mainHandler.post { if (!destroyed) scheduleSnapshotSync() }
                        return@post
                    }
                    val snapshots = sourceSnapshots()
                        ?: throw IllegalStateException("Notification snapshot unavailable")
                    reconcileSources(snapshots)
                    syncFlashlightMirror(snapshots)
                    syncConnectedDevicesMonitoring()
                    refreshPolicy.retain(snapshots.mapTo(mutableSetOf()) { it.key })
                    for (sbn in snapshots) {
                        if (sbn.packageName == packageName) continue
                        if (!refreshPolicy.needsRefresh(
                                sbn.key, sbn.postTime, SystemClock.elapsedRealtime(),
                                LiveUpdateNotifier.needsPeriodicRefresh(sbn.notification)
                            )) continue
                        processSafely(sbn)
                    }
                } catch (error: Throwable) {
                    Log.w(TAG, "Snapshot sync failed", error)
                    listenerConnected = false
                    mainHandler.post { scheduleRebind("snapshot_sync_failed") }
                }
                mainHandler.post { if (!destroyed) scheduleSnapshotSync() }
            }
            return
        }
    }

    override fun onCreate() {
        super.onCreate()
        activeInstance = this

        if (isUnsupportedDevice()) {
            FlashlightSourceState.clear()
            FlashlightForegroundService.stop(applicationContext)
            NetworkSpeedForegroundService.stop(applicationContext)
            LiveUpdateNotifier.cancelAllMirrored(applicationContext)
            return
        }
        if (!prefs.getConverterEnabled()) {
            LiveUpdateNotifier.cancelAllMirrored(applicationContext)
        }

        registerLockscreenStateReceiver()
        syncTorchMonitoring()
        syncChargingInfoMonitoring()
        syncConnectedDevicesMonitoring()
        LiveUpdateNotifier.ensureChannel(applicationContext)
        syncNetworkSpeedService()
        drainPendingReplySourceCancels()
        scheduleSnapshotSync()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        listenerConnected = true
        mainHandler.removeCallbacks(rebindRunnable)
        rebindAttempts = 0
        rebindScheduled = false
        KeepAliveForegroundService.sync(applicationContext, prefs)
        syncNetworkSpeedService()
        scheduleSnapshotSync()

        if (isUnsupportedDevice()) {
            FlashlightSourceState.clear()
            FlashlightForegroundService.stop(applicationContext)
            NetworkSpeedForegroundService.stop(applicationContext)
            LiveUpdateNotifier.cancelAllMirrored(applicationContext)
            return
        }

        processingHandler.post {
            if (destroyed || !listenerConnected) return@post
            refreshPolicy.invalidate()
            val snapshots = try {
                sourceSnapshots()
            } catch (error: Throwable) {
                Log.w(TAG, "Unable to read active notifications on connect", error)
                null
            } ?: return@post
            reconcileSources(snapshots)
            syncFlashlightMirror(snapshots)
            for (sbn in snapshots) {
                if (sbn.packageName != packageName) processSafely(sbn)
            }
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        listenerConnected = false
        mainHandler.removeCallbacks(snapshotSyncRunnable)
        snapshotSyncScheduled = false
        // Fork additions: drop the capsule and re-sync network speed on disconnect.
        LiveUpdateNotifier.cancelNotificationCapsule(applicationContext)
        syncNetworkSpeedService()
        scheduleRebind("listener_disconnected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        if (sbn.packageName == packageName) {
            return
        }
        processingHandler.post {
            if (!destroyed) {
                maybeSnoozeSystemChargingNotification(sbn)
                maybeSnoozeSystemFlashlightNotification(sbn)
                sourceLifecycle.posted(sbn.key)
                processSafely(sbn)
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return
        processingHandler.post {
            if (destroyed) return@post
            if (sbn.packageName == packageName) {
                LiveUpdateNotifier.handleMirroredRemoved(applicationContext, sbn)
                return@post
            }
            if (consumeSelfDismissedSourceKey(sbn.key)) return@post
            if (!sourceLifecycle.removed(sbn.key)) return@post
            // Rebuild any remaining members of an aggregate on the next snapshot.
            refreshPolicy.invalidate()
            refreshPolicy.remove(sbn.key)
            try {
                LiveUpdateNotifier.cancelMirrored(applicationContext, sbn)
            } catch (error: Throwable) {
                Log.e(TAG, "Failed to process removed notification: ${sbn.key}", error)
            }
        }
    }

    override fun onNotificationRemoved(
        sbn: StatusBarNotification?,
        rankingMap: RankingMap?,
        reason: Int
    ) {
        try {
            if (reason == NotificationListenerService.REASON_LISTENER_CANCEL) {
                Log.v(TAG, "Ignoring self-cancelled notification removal (REASON_LISTENER_CANCEL): ${sbn?.key}")
                return
            }
            handleNotificationRemoved(sbn, reason)
        } catch (e: Exception) {
            Log.e(TAG, "onNotificationRemoved(3-param): unhandled exception for ${sbn?.key}", e)
        }
    }

    private fun handleNotificationRemoved(sbn: StatusBarNotification?, reason: Int?) {
        sbn ?: return
        try {
            if (isUnsupportedDevice()) {
                return
            }
            if (consumeProgrammaticCancel(sbn)) {
                Log.v(TAG, "Ignoring programmatic notification removal: ${sbn.key}")
                refreshNotificationCapsuleFromActiveNotifications()
                return
            }
            if (sbn.packageName == packageName) {
                if (handleProtectedMirrorRemoval(sbn, reason)) {
                    return
                }
                LiveUpdateNotifier.handleMirroredRemoved(applicationContext, sbn)
                return
            }
            refreshChargingInfoFromActiveNotifications()
            if (LiveUpdateNotifier.isProgressSourceNotification(sbn)) {
                // Round 64: the store cancelled its own record —
                // e.g. the user pressed Cancel on the progress
                // mirror, whose action fires the store's intent.
                // Drop the mirror at once instead of waiting for the
                // snapshot reconcile. The echo of our own snooze
                // (round 63) still finds the record in the snoozed
                // list and falls through to the guarded path below,
                // so the mirror survives its source being hidden.
                val stillSnoozed = runCatching {
                    snoozedNotifications?.any { it.key == sbn.key } == true
                }.getOrDefault(false)
                if (!stillSnoozed) {
                    LiveUpdateNotifier.cancelMirrored(applicationContext, sbn)
                    refreshNotificationCapsuleFromActiveNotifications()
                    return
                }
            }
            if (consumeSelfDismissedSource(sbn)) {
                refreshNotificationCapsuleFromActiveNotifications()
                return
            }
            if (isFlashlightSourceNotification(sbn)) {
                forgetTrackedFlashlightSourceKey(sbn.key)
                if (consumeSelfDismissedFlashlightSourceKey(sbn.key)) {
                    refreshNotificationCapsuleFromActiveNotifications()
                    return
                }
                FlashlightSourceState.clear()
                FlashlightForegroundService.stop(applicationContext)
                refreshNotificationCapsuleFromActiveNotifications()
                return
            }
            if (prefs.getAutoDismissNowBarEnabled()) {
                LiveUpdateNotifier.cancelMirroredForSourceRemoval(applicationContext, sbn)
            }
            refreshNotificationCapsuleFromActiveNotifications()
        } catch (e: Exception) {
            Log.e(TAG, "handleNotificationRemoved: unhandled exception for ${sbn.key}", e)
        }
    }

    private fun isUnsupportedDevice(): Boolean {
        return DeviceBlocker.isBlockedDevice()
    }

    /**
     * FIX #1: Quyết định hủy sớm thông báo gốc để chặn race Wear OS.
     */
    private fun shouldEarlyDismissOriginalSource(sbn: StatusBarNotification): Boolean {
        if (!sbn.isClearable) {
            return false
        }
        val packageName = sbn.packageName
        val packageLower = packageName.lowercase()

        val appPresentationRemoveOriginal = runCatching {
            AppPresentationOverridesLoader
                .get(prefs)
                .resolve(packageLower)
                .removeOriginalMessage
        }.getOrDefault(false)
        if (appPresentationRemoveOriginal) {
            return true
        }

        if (prefs.getOtpRemoveOriginalMessageEnabled() && prefs.isOtpPackageAllowed(packageName)) {
            return true
        }
        if (prefs.getSmartRemoveOriginalMessageEnabled() && prefs.isSmartPackageAllowed(packageName)) {
            return true
        }
        if (
            prefs.getNotificationDedupEnabled() &&
            prefs.isNotificationDedupPackageAllowed(packageName)
        ) {
            return true
        }
        return false
    }

    /**
     * FIX #1: Hủy gốc ngay lập tức trước khi parse/build mirror.
     * FIX SOUND: Thêm delay 100ms để hệ thống kịp phát âm thanh trước khi hủy.
     */
    private fun maybeEarlyDismissSourceForWearRace(sbn: StatusBarNotification) {
        if (!shouldEarlyDismissOriginalSource(sbn)) {
            return
        }
        val sourceKey = sbn.key
        rememberProgrammaticCancelKeys(
            sourceKey,
            notificationIdentityKey(sbn.packageName, sbn.id, sbn.tag)
        )

        // Delay 1500ms để hệ thống kịp phát âm thanh trước khi hủy thông báo
        mainHandler.postDelayed({
            val cancelDirectRequested = runCatching {
                cancelNotification(sourceKey)
            }.onSuccess {
                Log.i(TAG, "Early-dismiss source via cancelNotification: $sourceKey")
            }.onFailure { error ->
                Log.w(TAG, "Early cancelNotification failed: $sourceKey", error)
            }.isSuccess

            val cancelBatchRequested = runCatching {
                cancelNotifications(arrayOf(sourceKey))
            }.onSuccess {
                Log.i(TAG, "Early-dismiss source via cancelNotifications: $sourceKey")
            }.onFailure { error ->
                Log.w(TAG, "Early cancelNotifications failed: $sourceKey", error)
            }.isSuccess

            val snoozeRequested = runCatching {
                snoozeNotification(sourceKey, ORIGINAL_SOURCE_SNOOZE_MS)
            }.onSuccess {
                Log.i(TAG, "Early-dismiss source via snooze fallback: $sourceKey")
            }.onFailure { error ->
                Log.w(TAG, "Early snoozeNotification failed: $sourceKey", error)
            }.isSuccess

            if (!cancelDirectRequested && !cancelBatchRequested && !snoozeRequested) {
                Log.w(TAG, "Early-dismiss failed completely for source: $sourceKey")
            }
        }, 1500) // Delay 1500ms
    }

    private fun drainPendingReplySourceCancels() {
        val targets = takePendingReplySourceCancels()
        targets.forEach { target ->
            if (!cancelReplySourceNotifications(target)) {
                rememberPendingReplySourceCancel(target)
            }
        }
    }

    private fun cancelReplySourceNotifications(target: ReplySourceCancelTarget): Boolean {
        val sourceKey = target.sourceKey.trim()
        if (sourceKey.isBlank()) {
            return false
        }
        rememberProgrammaticCancelKeys(sourceKey)
        return requestDismissOrSnoozeSourceNotification(
            sourceKey = sourceKey,
            label = "reply source notification"
        )
    }

    private fun consumeProgrammaticCancel(sbn: StatusBarNotification): Boolean {
        val keys = buildList {
            add(sbn.key)
            add(notificationIdentityKey(sbn.packageName, sbn.id, sbn.tag))
            if (sbn.packageName == packageName) {
                add(mirrorNotificationIdKey(sbn.id))
            }
        }
        return consumeProgrammaticCancelKeys(keys)
    }

    private fun syncNetworkSpeedService() {
        if (prefs.getNetworkSpeedEnabled() && !DeviceBlocker.isBlockedDevice()) {
            NetworkSpeedForegroundService.sync(applicationContext)
        } else {
            NetworkSpeedForegroundService.stop(applicationContext)
        }
    }

    private fun refreshNotificationCapsule(snapshots: Collection<StatusBarNotification>) {
        LiveUpdateNotifier.refreshNotificationCapsule(
            context = applicationContext,
            prefs = prefs,
            snapshots = snapshots
        )
    }

    private fun refreshNotificationCapsuleFromActiveNotifications() {
        val snapshots = try {
            activeNotifications?.toList().orEmpty()
        } catch (error: Throwable) {
            Log.w(TAG, "Unable to refresh notification capsule from active notifications", error)
            return
        }
        refreshNotificationCapsule(snapshots)
    }

    private fun clearNotificationsForPackage(packageName: String) {
        val normalizedPackageName = packageName.trim().lowercase()
        if (normalizedPackageName.isBlank()) {
            return
        }
        val snapshots = try {
            activeNotifications?.toList().orEmpty()
        } catch (error: Throwable) {
            Log.w(TAG, "Unable to clear package notifications", error)
            return
        }
        val sourceKeys = snapshots
            .filter { sbn -> sbn.packageName.lowercase() == normalizedPackageName }
            .filter { sbn -> sbn.isClearable }
            .map { sbn -> sbn.key }
            .distinct()

        if (sourceKeys.isEmpty()) {
            refreshNotificationCapsule(snapshots)
            return
        }

        sourceKeys.forEach { sourceKey ->
            runCatching {
                cancelNotification(sourceKey)
            }.onFailure { error ->
                Log.w(TAG, "cancelNotification failed for capsule clear: $sourceKey", error)
            }
        }
        runCatching {
            cancelNotifications(sourceKeys.toTypedArray())
        }.onFailure { error ->
            Log.w(TAG, "cancelNotifications failed for capsule clear", error)
        }
        mainHandler.postDelayed(
            { refreshNotificationCapsuleFromActiveNotifications() },
            CAPSULE_CLEAR_REFRESH_DELAY_MS
        )
    }

    private fun clearNotificationsForKeys(notificationKeys: Collection<String>) {
        val requestedKeys = notificationKeys
            .map { key -> key.trim() }
            .filter { key -> key.isNotBlank() }
            .toSet()
        if (requestedKeys.isEmpty()) {
            return
        }
        val snapshots = try {
            activeNotifications?.toList().orEmpty()
        } catch (error: Throwable) {
            Log.w(TAG, "Unable to clear notification capsule keys", error)
            return
        }
        val sourceKeys = snapshots
            .filter { sbn -> sbn.key in requestedKeys }
            .filter { sbn -> sbn.isClearable }
            .map { sbn -> sbn.key }
            .distinct()

        if (sourceKeys.isEmpty()) {
            refreshNotificationCapsule(snapshots)
            return
        }

        sourceKeys.forEach { sourceKey ->
            runCatching {
                cancelNotification(sourceKey)
            }.onFailure { error ->
                Log.w(TAG, "cancelNotification failed for capsule clear: $sourceKey", error)
            }
        }
        runCatching {
            cancelNotifications(sourceKeys.toTypedArray())
        }.onFailure { error ->
            Log.w(TAG, "cancelNotifications failed for capsule clear", error)
        }
        mainHandler.postDelayed(
            { refreshNotificationCapsuleFromActiveNotifications() },
            CAPSULE_CLEAR_REFRESH_DELAY_MS
        )
    }

    override fun onDestroy() {
        unregisterLockscreenStateReceiver()
        unregisterTorchCallbackIfNeeded()
        unregisterChargingInfoReceiverIfNeeded()
        unregisterDeviceConnectionReceiverIfNeeded()
        destroyed = true
        listenerConnected = false
        mainHandler.removeCallbacksAndMessages(null)
        rebindScheduled = false
        snapshotSyncScheduled = false
        if (activeInstance === this) {
            activeInstance = null
        }
        LiveUpdateNotifier.cancelNotificationCapsule(applicationContext)
        super.onDestroy()
    }

    private fun isSourceNotificationActive(key: String): Boolean? {
        return try {
            activeNotifications?.any { it.key == key } ?: false
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Snapshots for source-presence and processing: the active list
     * plus any SNOOZED smart-status sources (round 41). The VPN
     * source is deliberately snoozed once its capsule replaces it;
     * counting only active notifications made every sync/reconnect
     * treat that source as removed and cancel the capsule. The
     * candidate filter keeps notifications the user snoozed for
     * unrelated reasons out of the mirror pipeline.
     */
    private fun sourceSnapshots(): List<StatusBarNotification>? {
        val active = activeNotifications?.toList() ?: return null
        val snoozed = runCatching { snoozedNotifications?.toList() }
            .getOrNull()
            .orEmpty()
        if (snoozed.isEmpty()) {
            return active
        }
        val activeKeys = active.mapTo(HashSet()) { it.key }
        val snoozedSources = snoozed.filter { snoozedSbn ->
            snoozedSbn.key !in activeKeys &&
                (LiveUpdateNotifier.isSmartStatusSource(applicationContext, prefs, snoozedSbn) ||
                    LiveUpdateNotifier.isProgressSourceNotification(snoozedSbn))
        }
        return if (snoozedSources.isEmpty()) active else active + snoozedSources
    }

    private fun progressFingerprint(sbn: StatusBarNotification): String {
        return sbn.postTime.toString() + "|" + progressContentFingerprint(sbn)
    }

    // What the user would see: a pure repost of identical content
    // changes only the postTime, while a real state change (the
    // store reacting to a Pause tap with a "paused" state, progress
    // advancing) changes this fingerprint.
    private fun progressContentFingerprint(sbn: StatusBarNotification): String {
        val extras = sbn.notification.extras
        return listOf(
            extras.getInt(Notification.EXTRA_PROGRESS, -1).toString(),
            extras.getInt(Notification.EXTRA_PROGRESS_MAX, -1).toString(),
            extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false).toString(),
            extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),
            extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        ).joinToString("|")
    }

    private fun reconcileSources(snapshots: List<StatusBarNotification>) {
        val activeKeys = snapshots.asSequence().filter { it.packageName != packageName }.map { it.key }.toSet()
        // Fingerprint every present progress record (round 66): a
        // record whose content stops changing is a download the
        // owning app ended without deleting its (snoozed) record —
        // device-verified for the Play Store, whose record stays in
        // the snoozed list after the download itself is cancelled.
        val nowElapsed = SystemClock.elapsedRealtime()
        val presentProgressKeys = mutableSetOf<String>()
        for (snapshot in snapshots) {
            if (snapshot.packageName == packageName) continue
            if (!LiveUpdateNotifier.isProgressSourceNotification(snapshot)) continue
            presentProgressKeys.add(snapshot.key)
            val contentFingerprint = progressContentFingerprint(snapshot)
            val previousContent = progressContentFingerprints[snapshot.key]
            if (previousContent != contentFingerprint) {
                if (previousContent != null &&
                    (staleProgressFingerprints.containsKey(snapshot.key) ||
                        hardRetiredProgressContents.containsKey(snapshot.key))
                ) {
                    Log.i(TAG, "Retired progress record content changed: ${snapshot.key}")
                }
                // A pure repost (postTime only) must not lift the
                // stale mark (round 70): only a content change can.
                if (staleProgressFingerprints[snapshot.key] != null &&
                    staleProgressFingerprints[snapshot.key] != contentFingerprint
                ) {
                    staleProgressFingerprints.remove(snapshot.key)
                }
                progressContentFingerprints[snapshot.key] = contentFingerprint
            }
            val fingerprint = progressFingerprint(snapshot)
            val previous = progressFingerprints[snapshot.key]
            if (previous == null || previous.first != fingerprint) {
                progressFingerprints[snapshot.key] = fingerprint to nowElapsed
            }
        }
        progressFingerprints.keys.retainAll(presentProgressKeys)
        progressContentFingerprints.keys.retainAll(presentProgressKeys)
        hardRetiredProgressContents.keys.retainAll(presentProgressKeys)
        val probationIterator = progressProbation.entries.iterator()
        while (probationIterator.hasNext()) {
            val (probationKey, probation) = probationIterator.next()
            if (probationKey !in presentProgressKeys) {
                probationIterator.remove()
                continue
            }
            if (nowElapsed >= probation.second) {
                // The record moved once and froze again — the dying
                // store's final post, not a new download: re-harden
                // the retirement against the final content.
                hardRetiredProgressContents[probationKey] = probation.first
                probationIterator.remove()
            }
        }
        // Progress mirrors have no driver besides their source record
        // (round 65). The retention taken out when the source was
        // hidden (round 63) must lapse once the record is gone from
        // BOTH the active and the snoozed lists — both feed these
        // snapshots — because the store deleting its snoozed record
        // is not guaranteed to deliver a removal event (the owner's
        // cancelled download left a zombie capsule whose tap intent
        // the system had already cancelled with the record). Two
        // consecutive absent cycles guard against a transient gap.
        val progressIterator = progressDismissedSourceKeys.iterator()
        while (progressIterator.hasNext()) {
            val key = progressIterator.next()
            if (key in activeKeys) {
                progressAbsentStreaks.remove(key)
                // The record is still listed, but it may be a frozen
                // leftover (round 66): retire the mirror when an
                // action tap on it was never followed by a CONTENT
                // change (the store silently killed the download; a
                // mere repost of identical content — e.g. a final
                // post as the cancel lands — must not clear the
                // suspicion, while a Pause tap survives because the
                // store answers it with a changed state), or when
                // the record has been frozen for a minute
                // (cancelled inside the store app instead — no tap,
                // no record change, no removal event either way).
                val lastChange = progressFingerprints[key]?.second
                val tapAt = LiveUpdateNotifier.progressActionTapAt(key)
                if (tapAt != null) {
                    val baseline = progressTapContentBaselines[key]
                        ?: progressContentFingerprints[key]?.also { content ->
                            progressTapContentBaselines[key] = content
                        }
                    if (baseline != null && progressContentFingerprints[key] != baseline) {
                        LiveUpdateNotifier.clearProgressActionTap(key)
                        progressTapContentBaselines.remove(key)
                    }
                }
                val effectiveTapAt = LiveUpdateNotifier.progressActionTapAt(key)
                val tapDead = effectiveTapAt != null &&
                    nowElapsed - effectiveTapAt >= PROGRESS_TAP_DEAD_MS
                val staleDead = lastChange != null &&
                    nowElapsed - lastChange >= PROGRESS_STALE_MS
                if (tapDead || staleDead) {
                    Log.i(
                        TAG,
                        "Retire progress mirror: $key tapDead=$tapDead staleDead=$staleDead"
                    )
                    sourceLifecycle.cancelRetention(key)
                    progressContentFingerprints[key]?.let { content ->
                        staleProgressFingerprints[key] = content
                    }
                    LiveUpdateNotifier.clearProgressActionTap(key)
                    progressTapContentBaselines.remove(key)
                    progressIterator.remove()
                    // The reconcile below can never report this key
                    // missing — the frozen record still sits in the
                    // snapshot list — so cancel the mirror directly
                    // (round 68; the retention-only version was a
                    // no-op and the capsule survived every retire).
                    refreshPolicy.remove(key)
                    LiveUpdateNotifier.cancelMirroredSourceKey(applicationContext, key)
                }
                continue
            }
            val streak = (progressAbsentStreaks[key] ?: 0) + 1
            if (streak >= 2) {
                sourceLifecycle.cancelRetention(key)
                progressAbsentStreaks.remove(key)
                progressIterator.remove()
            } else {
                progressAbsentStreaks[key] = streak
            }
        }
        val missing = sourceLifecycle.reconcile(activeKeys, LiveUpdateNotifier.trackedSourceKeys())
        if (missing.isNotEmpty()) refreshPolicy.invalidate()
        for (key in missing) {
            refreshPolicy.remove(key)
            LiveUpdateNotifier.cancelMirroredSourceKey(applicationContext, key)
        }
    }

    private fun processSafely(sbn: StatusBarNotification) {
        try {
            if (processIncomingNotification(sbn)) {
                refreshPolicy.record(sbn.key, sbn.postTime, SystemClock.elapsedRealtime())
            } else {
                refreshPolicy.remove(sbn.key)
            }
        } catch (error: Throwable) {
            Log.e(TAG, "Failed to process notification: ${sbn.key}", error)
        }
    }

    private fun processIncomingNotification(sbn: StatusBarNotification): Boolean {
        // Hard retirement (round 70): a source the user cancelled
        // from the capsule stays dead even if the store posts one
        // last update while dying — it may mirror again only as a
        // demonstrably live download (its content moves twice in
        // quick succession; a final post never moves twice) or
        // after the record itself has left the system's lists.
        val hardDeadContent = hardRetiredProgressContents[sbn.key]
        if (hardDeadContent != null) {
            val content = progressContentFingerprint(sbn)
            if (content == hardDeadContent) return false
            val probation = progressProbation[sbn.key]
            if (probation != null && probation.first != content) {
                hardRetiredProgressContents.remove(sbn.key)
                progressProbation.remove(sbn.key)
            } else {
                if (probation == null) {
                    progressProbation[sbn.key] =
                        content to SystemClock.elapsedRealtime() + PROGRESS_PROBATION_MS
                }
                return false
            }
        }
        // A retired progress source (round 66) must not rebuild its
        // mirror from the frozen record the store left snoozed; it
        // may mirror again only when that record's content changes.
        val staleFingerprint = staleProgressFingerprints[sbn.key]
        if (staleFingerprint != null) {
            if (staleFingerprint == progressContentFingerprint(sbn)) return false
            staleProgressFingerprints.remove(sbn.key)
        }
        val ranking = Ranking()
        val channelName = try {
            if (currentRanking.getRanking(sbn.key, ranking)) ranking.channel?.name?.toString() else null
        } catch (_: Exception) { null }
        SourceChannelStore.observe(applicationContext, sbn.packageName, sbn.notification.channelId, channelName)
        val result = LiveUpdateNotifier.maybeMirror(applicationContext, prefs, sbn)
        if (!prefs.getConverterEnabled()) return false
        if (result.mirrored) {
            ConversionLogStore.upsertMirroredNotification(
                context = applicationContext,
                prefs = prefs,
                sbn = sbn,
                title = extractLogTitle(sbn),
                text = extractLogText(sbn.notification)
            )
        }
        maybeDismissOriginalSource(sbn, result)
        return !result.retryNeeded
    }

    private fun extractLogTitle(sbn: StatusBarNotification): String {
        val extras = sbn.notification.extras
        return extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: extras.getCharSequence(Notification.EXTRA_TITLE_BIG)?.toString()?.trim()
                ?.takeIf { it.isNotEmpty() }
            ?: AppMetadataCache.get(applicationContext, sbn.packageName).label
    }

    private fun extractLogText(notification: Notification): String {
        val extras = notification.extras
        return extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim()
                ?.takeIf { it.isNotEmpty() }
            ?: extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim()
                ?.takeIf { it.isNotEmpty() }
            ?: extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)?.toString()?.trim()
                ?.takeIf { it.isNotEmpty() }
            ?: extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
                ?.mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }
                ?.joinToString("\n")
                .orEmpty()
    }

    private fun syncTorchMonitoring() {
        if (prefs.getSmartFlashlightEnabled()) {
            ensureTorchCallbackRegistered()
        } else {
            unregisterTorchCallbackIfNeeded()
        }
    }

    // While the charging capsule is enabled it fully replaces the
    // system's own charging notification (SystemUI "charging_state"):
    // snooze that record on sight, the same approach that hid the
    // VPN source in round 39. A plain cancel is not offered by One UI
    // for this ongoing system record, and its channel is not user-
    // blockable either. The snooze dies with the record when the
    // system cancels it on unplug; if the feature is switched off
    // mid-charge there is no un-snooze API, so the system's entry
    // reappears at the latest on the next plug-in.
    private fun maybeSnoozeSystemChargingNotification(sbn: StatusBarNotification) {
        if (
            !prefs.getConverterEnabled() ||
            !prefs.getSmartChargingInfoEnabled() ||
            isUnsupportedDevice()
        ) {
            return
        }
        if (!LiveUpdateNotifier.isSystemChargingNotification(sbn)) {
            return
        }
        runCatching {
            snoozeNotification(sbn.key, SYSTEM_CHARGING_SOURCE_SNOOZE_MS)
        }.onFailure { error ->
            Log.w(TAG, "snoozeNotification failed for system charging source: ${sbn.key}", error)
        }
    }

    // Flashlight counterpart of the charging snooze above (round
    // 58): hide the system's flashlight notification in the posted
    // event itself instead of waiting for the snapshot-sync ->
    // tracked-key -> dismissal chain, which left the system card
    // visible for ~1.5s after torch-on (owner feedback). The round
    // 57 dismissal machinery stays as the backstop for records
    // posted before the listener connected. The key is remembered
    // and marked self-dismissed before snoozing, so a removal event
    // caused by our own snooze is not mistaken for the user turning
    // the torch off (which would tear the capsule down).
    private fun maybeSnoozeSystemFlashlightNotification(sbn: StatusBarNotification) {
        if (
            !prefs.getConverterEnabled() ||
            !prefs.getSmartFlashlightEnabled() ||
            isUnsupportedDevice()
        ) {
            return
        }
        if (!isFlashlightSourceNotification(sbn)) {
            return
        }
        rememberTrackedFlashlightSourceKey(sbn.key)
        synchronized(selfDismissLock) {
            selfDismissedFlashlightSourceKeys.add(sbn.key)
        }
        runCatching {
            snoozeNotification(sbn.key, FLASHLIGHT_SOURCE_SNOOZE_MS)
        }.onFailure { error ->
            Log.w(TAG, "snoozeNotification failed for system flashlight source: ${sbn.key}", error)
        }
    }

    // Counterpart of the snooze above (round 49): when the charging
    // capsule feature is switched off, the system's charging
    // notification must come back. There is no un-snooze API, so the
    // snoozed record is re-snoozed with a near-zero duration, which
    // hands it back to the shade effectively immediately.
    private fun restoreSystemChargingNotification() {
        val snoozed = runCatching { snoozedNotifications?.toList() }
            .getOrNull()
            .orEmpty()
        snoozed
            .filter { sbn -> LiveUpdateNotifier.isSystemChargingNotification(sbn) }
            .forEach { sbn ->
                runCatching {
                    snoozeNotification(sbn.key, SYSTEM_CHARGING_RESTORE_SNOOZE_MS)
                }.onFailure { error ->
                    Log.w(
                        TAG,
                        "Failed to restore system charging notification: ${sbn.key}",
                        error
                    )
                }
            }
    }

    private fun syncChargingInfoMonitoring(
        snapshots: Collection<StatusBarNotification>? = null
    ) {
        if (
            prefs.getSmartChargingInfoEnabled() &&
            prefs.getConverterEnabled() &&
            !isUnsupportedDevice()
        ) {
            ensureChargingInfoReceiverRegistered()
            // The charging capsule replaces the system's own charging
            // notification: keep that record snoozed while the capsule
            // feature is on (round 48). Re-checked here so toggling
            // the feature on mid-charge hides an already-posted one.
            (snapshots ?: activeNotificationSnapshotsForChargingInfo())
                .forEach(::maybeSnoozeSystemChargingNotification)
            LiveUpdateNotifier.refreshChargingInfo(
                context = applicationContext,
                prefs = prefs,
                activeNotifications = snapshots ?: activeNotificationSnapshotsForChargingInfo()
            )
        } else {
            restoreSystemChargingNotification()
            unregisterChargingInfoReceiverIfNeeded()
            LiveUpdateNotifier.cancelChargingInfo(applicationContext)
        }
    }

    private fun refreshChargingInfoFromActiveNotifications(
        removedNotification: StatusBarNotification? = null
    ) {
        if (!prefs.getSmartChargingInfoEnabled()) {
            return
        }
        LiveUpdateNotifier.refreshChargingInfo(
            context = applicationContext,
            prefs = prefs,
            activeNotifications = activeNotificationSnapshotsForChargingInfo(removedNotification)
        )
    }

    private fun activeNotificationSnapshotsForChargingInfo(
        extraNotification: StatusBarNotification? = null
    ): List<StatusBarNotification> {
        val snapshots = try {
            activeNotifications?.toList().orEmpty()
        } catch (error: Throwable) {
            Log.w(TAG, "Unable to read active notifications for charging info", error)
            emptyList()
        }
        extraNotification ?: return snapshots
        return (snapshots + extraNotification).distinctBy { sbn -> sbn.key }
    }

    private fun registerLockscreenStateReceiver() {
        if (lockscreenStateReceiverRegistered) {
            return
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(
                    lockscreenStateReceiver,
                    filter,
                    Context.RECEIVER_NOT_EXPORTED
                )
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(lockscreenStateReceiver, filter)
            }
        }.onSuccess {
            lockscreenStateReceiverRegistered = true
        }.onFailure { error ->
            Log.w(TAG, "Failed to register lockscreen state receiver", error)
        }
    }

    private fun unregisterLockscreenStateReceiver() {
        if (!lockscreenStateReceiverRegistered) {
            return
        }
        runCatching { unregisterReceiver(lockscreenStateReceiver) }
            .onFailure { error ->
                Log.w(TAG, "Failed to unregister lockscreen state receiver", error)
            }
        lockscreenStateReceiverRegistered = false
    }

    private fun ensureChargingInfoReceiverRegistered() {
        if (chargingInfoReceiverRegistered) {
            return
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(
                    chargingInfoReceiver,
                    filter,
                    Context.RECEIVER_NOT_EXPORTED
                )
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(chargingInfoReceiver, filter)
            }
        }.onSuccess {
            chargingInfoReceiverRegistered = true
        }.onFailure { error ->
            Log.w(TAG, "Failed to register charging info receiver", error)
        }
    }

    private fun unregisterChargingInfoReceiverIfNeeded() {
        if (!chargingInfoReceiverRegistered) {
            return
        }
        runCatching { unregisterReceiver(chargingInfoReceiver) }
            .onFailure { error ->
                Log.w(TAG, "Failed to unregister charging info receiver", error)
            }
        chargingInfoReceiverRegistered = false
    }

    // Connected-devices capsule monitoring (tier 1, round 71):
    // gated by the same "external devices" toggle as the
    // notification-text rule, so one switch runs the whole feature.
    // The capsule is re-pushed only when the scanned set changes;
    // the 4s snapshot sync calls this as a backstop for any
    // broadcast the receiver missed.
    private fun syncConnectedDevicesMonitoring() {
        if (
            prefs.getSmartExternalDevicesEnabled() &&
            prefs.getConverterEnabled() &&
            !isUnsupportedDevice()
        ) {
            ensureDeviceConnectionReceiverRegistered()
            val scanned = ConnectedDeviceScanner.scan(applicationContext)
            if (scanned != connectedDevices) {
                connectedDevices = scanned
                LiveUpdateNotifier.refreshConnectedDevicesCapsule(
                    applicationContext,
                    prefs,
                    scanned
                )
            }
        } else {
            unregisterDeviceConnectionReceiverIfNeeded()
            ConnectedDeviceScanner.releaseProfileProxies(applicationContext)
            connectedDevices = emptyList()
            LiveUpdateNotifier.cancelConnectedDevicesCapsule(applicationContext)
        }
    }

    private fun ensureDeviceConnectionReceiverRegistered() {
        if (deviceConnectionReceiverRegistered) {
            return
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(
                    deviceConnectionReceiver,
                    filter,
                    Context.RECEIVER_NOT_EXPORTED
                )
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(deviceConnectionReceiver, filter)
            }
        }.onSuccess {
            deviceConnectionReceiverRegistered = true
        }.onFailure { error ->
            Log.w(TAG, "Failed to register device connection receiver", error)
        }
    }

    private fun unregisterDeviceConnectionReceiverIfNeeded() {
        if (!deviceConnectionReceiverRegistered) {
            return
        }
        runCatching { unregisterReceiver(deviceConnectionReceiver) }
            .onFailure { error ->
                Log.w(TAG, "Failed to unregister device connection receiver", error)
            }
        deviceConnectionReceiverRegistered = false
    }

    private fun handleChargingInfoBatteryChanged(intent: Intent?) {
        val batteryIntent = intent?.takeIf { it.action == Intent.ACTION_BATTERY_CHANGED }
        LiveUpdateNotifier.refreshChargingInfo(
            context = applicationContext,
            prefs = prefs,
            batteryIntent = batteryIntent,
            activeNotifications = activeNotificationSnapshotsForChargingInfo()
        )
    }

    private fun handleLockscreenStateChanged(action: String?) {
        if (!prefs.getConverterEnabled() || !prefs.getHideLockscreenContentEnabled()) {
            return
        }
        Log.d(TAG, "Lockscreen privacy state changed: $action")
        mainHandler.removeCallbacks(lockscreenPrivacyRefreshRunnable)
        requestImmediateSnapshotSync()
        mainHandler.postDelayed(
            lockscreenPrivacyRefreshRunnable,
            LOCKSCREEN_PRIVACY_REFRESH_DELAY_MS
        )
    }

    private fun ensureTorchCallbackRegistered() {
        if (isTorchCallbackRegistered) {
            return
        }
        runCatching { flashlightController.registerTorchCallback(torchCallback) }
            .onSuccess {
                isTorchCallbackRegistered = true
                Log.d(TAG, "Listener torch callback registered")
            }
            .onFailure { error ->
                Log.w(TAG, "Failed to register listener torch callback", error)
            }
    }

    private fun refreshTorchCallbackRegistration() {
        unregisterTorchCallbackIfNeeded()
        ensureTorchCallbackRegistered()
    }

    private fun unregisterTorchCallbackIfNeeded() {
        if (!isTorchCallbackRegistered) {
            return
        }
        runCatching { flashlightController.unregisterTorchCallback(torchCallback) }
            .onFailure { error ->
                Log.w(TAG, "Failed to unregister listener torch callback", error)
            }
        isTorchCallbackRegistered = false
    }

    private fun handleObservedTorchState(enabled: Boolean) {
        if (!prefs.getSmartFlashlightEnabled()) {
            if (!enabled) {
                resetFlashlightSourceDismissState()
                FlashlightSourceState.clear()
                FlashlightForegroundService.stop(applicationContext)
            }
            return
        }

        if (enabled) {
            FlashlightForegroundService.sync(applicationContext)
            return
        }

        clearTrackedFlashlightSourceKey()
        FlashlightSourceState.clear()
        FlashlightForegroundService.stop(applicationContext)
    }

    private fun handleObservedTorchUnavailable() {
        resetFlashlightSourceDismissState()
        FlashlightSourceState.clear()
        FlashlightForegroundService.stop(applicationContext)
    }

    private fun syncFlashlightMirror(snapshots: Collection<StatusBarNotification>) {
        syncTorchMonitoring()
        if (!prefs.getSmartFlashlightEnabled()) {
            restoreSnoozedFlashlightSource()
            resetFlashlightSourceDismissState()
            FlashlightSourceState.clear()
            FlashlightForegroundService.stop(applicationContext)
            return
        }

        val sourceNotification = snapshots.firstOrNull(::isFlashlightSourceNotification)
        if (sourceNotification != null) {
            rememberTrackedFlashlightSourceKey(sourceNotification.key)
            FlashlightSourceState.updateFrom(
                context = applicationContext,
                packageName = sourceNotification.packageName,
                notification = sourceNotification.notification
            )
            FlashlightForegroundService.sync(applicationContext)
        } else {
            clearTrackedFlashlightSourceKey()
            if (FlashlightForegroundService.hasActiveNotification()) {
                return
            }
            FlashlightSourceState.clear()
            FlashlightForegroundService.stop(applicationContext)
        }
    }

    private fun isFlashlightSourceNotification(sbn: StatusBarNotification): Boolean {
        if (sbn.packageName != FLASHLIGHT_SOURCE_PACKAGE) {
            return false
        }
        return sbn.notification.channelId == FLASHLIGHT_SOURCE_CHANNEL_ID || sbn.tag == FLASHLIGHT_SOURCE_TAG
    }

    fun requestImmediateFlashlightSnapshotSync() {
        mainHandler.removeCallbacks(snapshotSyncRunnable)
        if (prefs.getSmartFlashlightEnabled()) {
            refreshTorchCallbackRegistration()
        } else {
            resetFlashlightSourceDismissState()
            unregisterTorchCallbackIfNeeded()
        }
        snapshotSyncScheduled = true
        mainHandler.post(snapshotSyncRunnable)
    }

    fun requestImmediateChargingInfoSync() {
        syncChargingInfoMonitoring()
    }

    fun requestImmediateSnapshotSync() {
        mainHandler.removeCallbacks(snapshotSyncRunnable)
        snapshotSyncScheduled = true
        mainHandler.post(snapshotSyncRunnable)
    }

    // Instant retirement for a Cancel tap on the progress mirror
    // itself (round 69): the frozen-record suspicion window stays
    // as the backstop for cancels that happen inside the source
    // app, but the user's own tap needs no waiting — the download
    // is ending by their hand and the record will never change
    // again. Mirrors the reconcile retire branch; kept separate
    // because that loop must remove through its own iterator.
    fun retireProgressSourceNow(sourceKey: String) {
        processingHandler.post {
            progressContentFingerprints[sourceKey]?.let { content ->
                staleProgressFingerprints[sourceKey] = content
                hardRetiredProgressContents[sourceKey] = content
            }
            sourceLifecycle.cancelRetention(sourceKey)
            progressDismissedSourceKeys.remove(sourceKey)
            progressTapContentBaselines.remove(sourceKey)
            LiveUpdateNotifier.clearProgressActionTap(sourceKey)
            refreshPolicy.remove(sourceKey)
            LiveUpdateNotifier.cancelMirroredSourceKey(applicationContext, sourceKey)
        }
    }

    private fun requestTrackedFlashlightSourceDismissal() {
        if (!prefs.getSmartFlashlightEnabled()) {
            resetFlashlightSourceDismissState()
            Log.v(TAG, "Skip flashlight source dismiss request: smart flashlight disabled")
            return
        }
        mainHandler.post {
            dismissTrackedFlashlightSourceNotification()
        }
    }

    private fun dismissTrackedFlashlightSourceNotification() {
        if (!prefs.getSmartFlashlightEnabled() || !FlashlightForegroundService.hasActiveNotification()) {
            resetFlashlightSourceDismissState()
            Log.v(TAG, "Skip flashlight source dismiss: feature disabled or LiveBridge notification hidden")
            return
        }
        val sourceKey = synchronized(selfDismissLock) {
            trackedFlashlightSourceKey?.also(selfDismissedFlashlightSourceKeys::add)
        }
        if (sourceKey == null) {
            Log.v(TAG, "Skip flashlight source dismiss: no tracked key")
            return
        }

        val cancelDirectRequested = runCatching {
            cancelNotification(sourceKey)
        }.onSuccess {
            Log.i(TAG, "Requested flashlight source cancel via cancelNotification: $sourceKey")
        }.onFailure { error ->
            Log.w(TAG, "cancelNotification failed for flashlight source: $sourceKey", error)
        }.isSuccess

        val cancelBatchRequested = runCatching {
            cancelNotifications(arrayOf(sourceKey))
        }.onSuccess {
            Log.i(TAG, "Requested flashlight source cancel via cancelNotifications: $sourceKey")
        }.onFailure { error ->
            Log.w(TAG, "cancelNotifications failed for flashlight source: $sourceKey", error)
        }.isSuccess

        val snoozeRequested = runCatching {
            snoozeNotification(sourceKey, FLASHLIGHT_SOURCE_SNOOZE_MS)
        }.onSuccess {
            Log.i(TAG, "Requested flashlight source snooze fallback: $sourceKey")
        }.onFailure { error ->
            Log.w(TAG, "snoozeNotification failed for flashlight source: $sourceKey", error)
        }.isSuccess

        if (!cancelDirectRequested && !cancelBatchRequested && !snoozeRequested) {
            forgetSelfDismissedFlashlightSourceKey(sourceKey)
            Log.w(TAG, "Unable to dismiss or snooze SystemUI flashlight notification: $sourceKey")
            return
        }

        mainHandler.postDelayed(
            { verifyFlashlightSourceDismissal(sourceKey) },
            FLASHLIGHT_SOURCE_VERIFY_DELAY_MS
        )
    }

    private fun verifyFlashlightSourceDismissal(sourceKey: String) {
        if (!prefs.getSmartFlashlightEnabled() || !FlashlightForegroundService.hasActiveNotification()) {
            Log.v(TAG, "Skip flashlight source dismissal verify: feature disabled or LiveBridge notification hidden")
            return
        }
        val stillPresent = runCatching {
            activeNotifications?.any { it.key == sourceKey } == true
        }.getOrElse { error ->
            Log.w(TAG, "Failed to verify flashlight source dismissal: $sourceKey", error)
            false
        }
        if (!stillPresent) {
            Log.i(TAG, "Flashlight source no longer active after dismissal: $sourceKey")
            return
        }

        val snoozeRequested = runCatching {
            snoozeNotification(sourceKey, FLASHLIGHT_SOURCE_SNOOZE_MS)
        }.onSuccess {
            Log.i(TAG, "Retried flashlight source snooze after failed dismissal: $sourceKey")
        }.onFailure { error ->
            Log.w(TAG, "Retry snooze failed for flashlight source: $sourceKey", error)
        }.isSuccess

        if (!snoozeRequested) {
            Log.w(TAG, "Flashlight source is still active after all dismissal attempts: $sourceKey")
        }
    }

    private fun maybeDismissOriginalSource(
        sbn: StatusBarNotification,
        result: LiveUpdateNotifier.MirrorResult
    ) {
        if (!result.mirrored) {
            return
        }
        if (result.retainOriginal) {
            return
        }
        // Progress retirement tracking must register BEFORE the
        // already-snoozed early return below (round 68): a record
        // snoozed by an earlier build/process arrives pre-snoozed,
        // and skipping registration there left its mirror with no
        // retirement path at all (device-log verified — taps were
        // recorded but the key was never tracked).
        if (result.removeSource && LiveUpdateNotifier.isProgressSourceNotification(sbn)) {
            progressDismissedSourceKeys.add(sbn.key)
            sourceLifecycle.retainMirrorAfterRemoval(sbn.key)
            Log.i(TAG, "Progress source tracked for retirement: ${sbn.key}")
        }
        // A source that is already snoozed (round 41) is already
        // hidden; attempting to cancel it could delete the snoozed
        // record and orphan the mirror that replaced it.
        val isSnoozed = runCatching {
            snoozedNotifications?.any { it.key == sbn.key } == true
        }.getOrDefault(false)
        if (isSnoozed) {
            return
        }
        // A VPN mirror replaces its ongoing source outright (round 38):
        // its removeSource request outranks the clearable check that
        // otherwise protects persistent notifications from dismissal.
        if (!sbn.isClearable && !result.removeSource) {
            return
        }
        val appPresentationRemoveOriginal = AppPresentationOverridesLoader
            .get(prefs)
            .resolve(sbn.packageName.lowercase())
            .removeOriginalMessage
        val legacyDedup =
            prefs.getNotificationDedupEnabled() &&
                prefs.isNotificationDedupPackageAllowed(sbn.packageName) &&
                when (prefs.getNotificationDedupMode()) {
                    "otp_only" -> result.dedupKind == LiveUpdateNotifier.MirrorDedupKind.OTP
                    else -> {
                        result.dedupKind == LiveUpdateNotifier.MirrorDedupKind.OTP ||
                            result.dedupKind == LiveUpdateNotifier.MirrorDedupKind.STATUS
                    }
                }
        val upstreamDismiss = when (result.dedupKind) {
            LiveUpdateNotifier.MirrorDedupKind.OTP -> {
                prefs.getOtpRemoveOriginalMessageEnabled() &&
                    prefs.isOtpPackageAllowed(sbn.packageName)
            }
            LiveUpdateNotifier.MirrorDedupKind.STATUS -> {
                prefs.getSmartRemoveOriginalMessageEnabled() &&
                    prefs.isSmartPackageAllowed(sbn.packageName)
            }
            else -> false
        }
        val shouldDismiss =
            result.removeSource ||
                appPresentationRemoveOriginal ||
                legacyDedup ||
                upstreamDismiss
        if (!shouldDismiss) {
            return
        }

        val persistent = sbn.notification.flags and
            (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE) != 0 ||
            sbn.notification.category == Notification.CATEGORY_SERVICE ||
            sbn.packageName == "com.android.bluetooth"
        // Limit identical reposts, not a burst of different messages in the same app/channel.
        val title = sbn.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val contentRevision = (title + "\n" + extractLogText(sbn.notification)).hashCode()
        val removalKey = "${sbn.packageName}|${sbn.notification.channelId}|${sbn.id}|$contentRevision"
        if (!result.removeSource &&
            !originalRemovalPolicy.mayRemove(removalKey, SystemClock.elapsedRealtime(), persistent)
        ) return
        rememberSelfDismissedSourceKey(sbn.key)
        sourceLifecycle.retainMirrorAfterRemoval(sbn.key)
        try {
            cancelNotification(sbn.key)
        } catch (error: Throwable) {
            sourceLifecycle.cancelRetention(sbn.key)
            forgetSelfDismissedSourceKey(sbn.key)
            Log.e(TAG, "Failed to auto-dismiss original notification: ${sbn.key}", error)
        }
        if (result.removeSource) {
            // One UI silently keeps foreground-service notifications
            // that a listener cancels (verified on the owner's Flip5,
            // round 39: no error, no removal event, no repost — the
            // original simply stays). Verify shortly after the cancel;
            // if the source is still up, snooze it out of the shade
            // instead — the capsule has already replaced it.
            processingHandler.postDelayed(
                {
                    val stillActive = runCatching {
                        activeNotifications?.any { it.key == sbn.key } == true
                    }.getOrDefault(false)
                    if (stillActive) {
                        runCatching {
                            snoozeNotification(sbn.key, VPN_SOURCE_SNOOZE_MS)
                        }.onFailure { error ->
                            Log.w(TAG, "Failed to snooze VPN source: ${sbn.key}", error)
                        }
                    }
                },
                VPN_SOURCE_DISMISS_VERIFY_DELAY_MS
            )
        }
    }

    private fun scheduleOriginalSourceDismissal(
        sourceSbn: StatusBarNotification,
        mirrorNotificationId: Int,
        mirrorKey: String?,
        attempt: Int
    ) {
        val delayMs = if (attempt == 0) {
            ORIGINAL_DISMISS_INITIAL_GRACE_MS
        } else {
            ORIGINAL_DISMISS_RETRY_DELAY_MS
        }
        mainHandler.postDelayed(
            {
                dismissOriginalSourceWhenMirrorActive(
                    sourceSbn = sourceSbn,
                    mirrorNotificationId = mirrorNotificationId,
                    mirrorKey = mirrorKey,
                    attempt = attempt
                )
            },
            delayMs
        )
    }

    private fun dismissOriginalSourceWhenMirrorActive(
        sourceSbn: StatusBarNotification,
        mirrorNotificationId: Int,
        mirrorKey: String?,
        attempt: Int
    ) {
        val sourceKey = sourceSbn.key
        val active = try {
            activeNotifications
        } catch (error: Throwable) {
            Log.w(TAG, "Failed to verify mirror before original dismissal: $sourceKey", error)
            retryOriginalSourceDismissal(sourceSbn, mirrorNotificationId, mirrorKey, attempt)
            return
        }
        if (active == null) {
            retryOriginalSourceDismissal(sourceSbn, mirrorNotificationId, mirrorKey, attempt)
            return
        }

        val snapshots = active.toList()
        if (snapshots.none { it.key == sourceKey }) {
            Log.v(TAG, "Skip original notification dismissal: source is no longer active: $sourceKey")
            return
        }
        if (snapshots.none { isExpectedMirrorNotification(it, mirrorNotificationId) }) {
            retryOriginalSourceDismissal(sourceSbn, mirrorNotificationId, mirrorKey, attempt)
            return
        }

        rememberSelfDismissedSource(sourceSbn, mirrorNotificationId, mirrorKey)
        val dismissRequested = requestDismissOrSnoozeSourceNotification(
            sourceKey = sourceKey,
            label = "original notification"
        )
        if (!dismissRequested) {
            forgetSelfDismissedSourceKey(sourceKey)
        }
    }

    private fun retryOriginalSourceDismissal(
        sourceSbn: StatusBarNotification,
        mirrorNotificationId: Int,
        mirrorKey: String?,
        attempt: Int
    ) {
        if (attempt >= ORIGINAL_DISMISS_MAX_ATTEMPTS) {
            Log.w(TAG, "Skip original notification dismissal: mirror not active for ${sourceSbn.key}")
            return
        }
        scheduleOriginalSourceDismissal(
            sourceSbn = sourceSbn,
            mirrorNotificationId = mirrorNotificationId,
            mirrorKey = mirrorKey,
            attempt = attempt + 1
        )
    }

    private fun isExpectedMirrorNotification(
        sbn: StatusBarNotification,
        mirrorNotificationId: Int
    ): Boolean {
        if (sbn.packageName != packageName || sbn.id != mirrorNotificationId) {
            return false
        }
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            LiveUpdateNotifier.isMirrorNotificationChannel(sbn.notification.channelId)
    }

    private fun requestDismissOrSnoozeSourceNotification(
        sourceKey: String,
        label: String
    ): Boolean {
        val cancelDirectRequested = runCatching {
            cancelNotification(sourceKey)
        }.onSuccess {
            Log.i(TAG, "Requested $label cancel via cancelNotification: $sourceKey")
        }.onFailure { error ->
            Log.w(TAG, "cancelNotification failed for $label: $sourceKey", error)
        }.isSuccess

        val cancelBatchRequested = runCatching {
            cancelNotifications(arrayOf(sourceKey))
        }.onSuccess {
            Log.i(TAG, "Requested $label cancel via cancelNotifications: $sourceKey")
        }.onFailure { error ->
            Log.w(TAG, "cancelNotifications failed for $label: $sourceKey", error)
        }.isSuccess

        val snoozeRequested = runCatching {
            snoozeNotification(sourceKey, ORIGINAL_SOURCE_SNOOZE_MS)
        }.onSuccess {
            Log.i(TAG, "Requested $label snooze fallback: $sourceKey")
        }.onFailure { error ->
            Log.w(TAG, "snoozeNotification failed for $label: $sourceKey", error)
        }.isSuccess

        if (!cancelDirectRequested && !cancelBatchRequested && !snoozeRequested) {
            Log.w(TAG, "Unable to dismiss or snooze $label: $sourceKey")
            return false
        }
        return true
    }

    private fun rememberSelfDismissedSource(
        sbn: StatusBarNotification,
        mirrorNotificationId: Int,
        mirrorKey: String?
    ) {
        synchronized(selfDismissLock) {
            val now = SystemClock.elapsedRealtime()
            pruneProtectedDismissalsLocked(now)
            val record = ProtectedSourceDismissal(
                sourceKey = sbn.key,
                sourcePackageName = sbn.packageName,
                sourceId = sbn.id,
                sourceTag = sbn.tag,
                sourceGroupKey = sbn.groupKey,
                sourceSbn = sbn,
                mirrorNotificationId = mirrorNotificationId,
                mirrorKey = mirrorKey,
                expiresAtMs = now + SELF_DISMISS_PROTECTION_MS
            )
            selfDismissedSourcesByKey[sbn.key]?.let(::removeProtectedDismissalLocked)
            selfDismissedSourcesByKey[sbn.key] = record
            protectedMirrorDismissalsById[mirrorNotificationId] = record
        }
    }

    private fun rememberSelfDismissedSourceKey(sbnKey: String) {
        synchronized(selfDismissLock) {
            selfDismissedSourceKeys.add(sbnKey)
        }
    }

    private fun consumeSelfDismissedSourceKey(sbnKey: String): Boolean {
        return synchronized(selfDismissLock) {
            selfDismissedSourceKeys.remove(sbnKey)
        }
    }

    private fun forgetSelfDismissedSourceKey(sbnKey: String) {
        synchronized(selfDismissLock) {
            selfDismissedSourceKeys.remove(sbnKey)
            val record = selfDismissedSourcesByKey[sbnKey] ?: return
            removeProtectedDismissalLocked(record)
        }
    }

    private fun consumeSelfDismissedSource(sbn: StatusBarNotification): Boolean {
        val record = synchronized(selfDismissLock) {
            val now = SystemClock.elapsedRealtime()
            pruneProtectedDismissalsLocked(now)
            val match = selfDismissedSourcesByKey[sbn.key]
                ?: selfDismissedSourcesByKey.values.firstOrNull { it.matchesSource(sbn) }
            match?.also { it.sourceRemovalConsumed = true }
        }
        if (record != null) {
            Log.v(
                TAG,
                "Skip mirrored cancel for self-dismissed source: ${sbn.key} -> ${record.mirrorKey}"
            )
            return true
        }
        return false
    }

    private fun handleProtectedMirrorRemoval(
        sbn: StatusBarNotification,
        reason: Int?
    ): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            sbn.notification.channelId != LiveUpdateNotifier.CHANNEL_ID
        ) {
            return false
        }

        val mirrorId = sbn.id
        val shouldScheduleRepost = synchronized(selfDismissLock) {
            val now = SystemClock.elapsedRealtime()
            pruneProtectedDismissalsLocked(now)
            val record = protectedMirrorDismissalsById[mirrorId] ?: return false
            if (isUserDrivenMirrorRemoval(reason)) {
                removeProtectedDismissalLocked(record)
                return false
            }
            if (record.mirrorRepostAttempts >= PROTECTED_MIRROR_MAX_REPOSTS) {
                Log.w(
                    TAG,
                    "Protected mirror removed again; stop reposting id=$mirrorId reason=${removalReasonName(reason)}"
                )
                removeProtectedDismissalLocked(record)
                return true
            }
            record.mirrorRepostAttempts += 1
            true
        }

        if (shouldScheduleRepost) {
            Log.i(
                TAG,
                "Mirror removal protected after original dismissal: id=$mirrorId reason=${removalReasonName(reason)}"
            )
            scheduleProtectedMirrorRepost(mirrorId)
        }
        return true
    }

    private fun scheduleProtectedMirrorRepost(mirrorNotificationId: Int) {
        mainHandler.postDelayed(
            { repostProtectedMirrorIfMissing(mirrorNotificationId) },
            PROTECTED_MIRROR_REPOST_DELAY_MS
        )
    }

    private fun repostProtectedMirrorIfMissing(mirrorNotificationId: Int) {
        val record = synchronized(selfDismissLock) {
            val now = SystemClock.elapsedRealtime()
            pruneProtectedDismissalsLocked(now)
            protectedMirrorDismissalsById[mirrorNotificationId]
        } ?: return

        val mirrorStillActive = try {
            activeNotifications?.any { isExpectedMirrorNotification(it, mirrorNotificationId) } == true
        } catch (error: Throwable) {
            Log.w(TAG, "Failed to verify protected mirror before repost: $mirrorNotificationId", error)
            false
        }
        if (mirrorStillActive) {
            return
        }

        val result = LiveUpdateNotifier.maybeMirror(applicationContext, prefs, record.sourceSbn)
        if (!result.mirrored) {
            Log.w(
                TAG,
                "Protected mirror repost skipped: source no longer mirrors ${record.sourceKey}"
            )
        }
    }

    private fun pruneProtectedDismissalsLocked(now: Long) {
        val expiredRecords = selfDismissedSourcesByKey.values
            .filter { it.expiresAtMs < now }
            .toList()
        expiredRecords.forEach(::removeProtectedDismissalLocked)
    }

    private fun removeProtectedDismissalLocked(record: ProtectedSourceDismissal) {
        val sourceKeys = selfDismissedSourcesByKey
            .filterValues { it === record }
            .keys
            .toList()
        sourceKeys.forEach(selfDismissedSourcesByKey::remove)
        val mirrorIds = protectedMirrorDismissalsById
            .filterValues { it === record }
            .keys
            .toList()
        mirrorIds.forEach(protectedMirrorDismissalsById::remove)
    }

    private fun isUserDrivenMirrorRemoval(reason: Int?): Boolean {
        return reason == NotificationListenerService.REASON_CANCEL ||
            reason == NotificationListenerService.REASON_CANCEL_ALL ||
            reason == NotificationListenerService.REASON_CLICK
    }

    private fun removalReasonName(reason: Int?): String {
        return when (reason) {
            null -> "unknown"
            NotificationListenerService.REASON_CANCEL -> "cancel"
            NotificationListenerService.REASON_CANCEL_ALL -> "cancel_all"
            NotificationListenerService.REASON_CLICK -> "click"
            NotificationListenerService.REASON_APP_CANCEL -> "app_cancel"
            NotificationListenerService.REASON_APP_CANCEL_ALL -> "app_cancel_all"
            NotificationListenerService.REASON_LISTENER_CANCEL -> "listener_cancel"
            NotificationListenerService.REASON_LISTENER_CANCEL_ALL -> "listener_cancel_all"
            else -> "reason_$reason"
        }
    }

    private fun rememberTrackedFlashlightSourceKey(sbnKey: String) {
        synchronized(selfDismissLock) {
            selfDismissedFlashlightSourceKeys.remove(sbnKey)
            trackedFlashlightSourceKey = sbnKey
        }
    }

    private fun forgetTrackedFlashlightSourceKey(sbnKey: String) {
        synchronized(selfDismissLock) {
            if (trackedFlashlightSourceKey == sbnKey) {
                trackedFlashlightSourceKey = null
            }
        }
    }

    private fun clearTrackedFlashlightSourceKey() {
        synchronized(selfDismissLock) {
            trackedFlashlightSourceKey = null
        }
    }

    // Counterpart of the long source snooze (round 55, same rule as
    // the charging capsule in round 49): when the flashlight
    // feature is switched off, hand the system's flashlight
    // notification back by re-snoozing it for ~zero time — there is
    // no un-snooze API.
    private fun restoreSnoozedFlashlightSource() {
        val snoozed = runCatching { snoozedNotifications?.toList() }
            .getOrNull()
            .orEmpty()
        snoozed
            .filter(::isFlashlightSourceNotification)
            .forEach { sbn ->
                runCatching {
                    snoozeNotification(sbn.key, FLASHLIGHT_SOURCE_RESTORE_SNOOZE_MS)
                }.onFailure { error ->
                    Log.w(
                        TAG,
                        "Failed to restore system flashlight notification: ${sbn.key}",
                        error
                    )
                }
            }
    }

    private fun resetFlashlightSourceDismissState() {
        synchronized(selfDismissLock) {
            trackedFlashlightSourceKey = null
            selfDismissedFlashlightSourceKeys.clear()
        }
    }

    private fun forgetSelfDismissedFlashlightSourceKey(sbnKey: String) {
        synchronized(selfDismissLock) {
            selfDismissedFlashlightSourceKeys.remove(sbnKey)
        }
    }

    private fun consumeSelfDismissedFlashlightSourceKey(sbnKey: String): Boolean {
        return synchronized(selfDismissLock) {
            selfDismissedFlashlightSourceKeys.remove(sbnKey)
        }
    }

    private fun scheduleRebind(reason: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return
        }
        if (listenerConnected || rebindScheduled || !isListenerEnabled(applicationContext)) {
            return
        }
        if (rebindAttempts >= MAX_REBIND_ATTEMPTS) {
            Log.w(TAG, "Listener rebind attempts exhausted ($reason)")
            return
        }

        val delayMs = min(MAX_REBIND_DELAY_MS, INITIAL_REBIND_DELAY_MS shl rebindAttempts)
        rebindScheduled = true
        mainHandler.postDelayed(rebindRunnable, delayMs)
    }

    private fun scheduleSnapshotSync() {
        if (!listenerConnected || snapshotSyncScheduled) {
            return
        }
        snapshotSyncScheduled = true
        mainHandler.postDelayed(snapshotSyncRunnable, SNAPSHOT_SYNC_INTERVAL_MS)
    }

    companion object {
        fun isConnected(): Boolean = activeInstance?.listenerConnected == true

        internal fun invalidateSnapshotCache() {
            val listener = activeInstance ?: return
            listener.processingHandler.post { listener.refreshPolicy.invalidate() }
        }

        fun isSourceNotificationActive(key: String): Boolean? {
            val listener = activeInstance ?: return null
            return if (listener.listenerConnected) listener.isSourceNotificationActive(key) else null
        }
        private const val TAG = "LiveUpdateListener"
        private const val INITIAL_REBIND_DELAY_MS = 1_000L
        private const val MAX_REBIND_DELAY_MS = 30_000L
        private const val MAX_REBIND_ATTEMPTS = 6
        private const val SNAPSHOT_SYNC_INTERVAL_MS = 4_000L
        private const val PROGRESS_TAP_DEAD_MS = 8_000L
        private const val PROGRESS_STALE_MS = 60_000L
        private const val PROGRESS_PROBATION_MS = 10_000L
        private const val ORIGINAL_DISMISS_INITIAL_GRACE_MS = 750L
        private const val VPN_SOURCE_DISMISS_VERIFY_DELAY_MS = 1200L
        private const val VPN_SOURCE_SNOOZE_MS = 12 * 60 * 60 * 1000L
        private const val SYSTEM_CHARGING_SOURCE_SNOOZE_MS = 12 * 60 * 60 * 1000L
        private const val SYSTEM_CHARGING_RESTORE_SNOOZE_MS = 1_000L
        private const val ORIGINAL_DISMISS_RETRY_DELAY_MS = 250L
        private const val ORIGINAL_DISMISS_MAX_ATTEMPTS = 6
        private const val ORIGINAL_SOURCE_SNOOZE_MS = 500L
        private const val SELF_DISMISS_PROTECTION_MS = 10_000L
        private const val PROTECTED_MIRROR_REPOST_DELAY_MS = 350L
        private const val PROTECTED_MIRROR_MAX_REPOSTS = 2
        private const val LOCKSCREEN_PRIVACY_REFRESH_DELAY_MS = 650L
        private const val CAPSULE_CLEAR_REFRESH_DELAY_MS = 250L
        // The snooze is the fallback that actually hides the
        // SystemUI flashlight source on One UI builds that silently
        // ignore listener cancels (the round-39 lesson): at 1.5s the
        // source simply popped back, which is the duplication users
        // reported. 12h matches the VPN / system-charging sources;
        // the snooze dies with the record when the torch turns off.
        private const val FLASHLIGHT_SOURCE_SNOOZE_MS = 12 * 60 * 60 * 1000L
        private const val FLASHLIGHT_SOURCE_RESTORE_SNOOZE_MS = 1_000L
        private const val FLASHLIGHT_SOURCE_VERIFY_DELAY_MS = 300L
        private const val FLASHLIGHT_SOURCE_PACKAGE = "com.android.systemui"
        private const val FLASHLIGHT_SOURCE_CHANNEL_ID = "FLASHLIGHT_ONGOING"
        private const val FLASHLIGHT_SOURCE_TAG = "Flashlight"
        private const val REPLY_SOURCE_CANCEL_TTL_MS = 15_000L
        private const val PROGRAMMATIC_CANCEL_TTL_MS = 15_000L

        @Volatile
        internal var activeInstance: LiveUpdateNotificationListenerService? = null
            private set
        private val pendingReplySourceCancelLock = Any()
        private val pendingReplySourceCancels = mutableMapOf<String, ReplySourceCancelTarget>()
        private val programmaticCancelDeadlines =
            java.util.concurrent.ConcurrentHashMap<String, Long>()

        @Volatile
        var lastHeartbeatMs: Long = 0L
            private set

        fun isListenerAlive(maxStaleMs: Long = 30_000L): Boolean {
            if (activeInstance == null) return false
            if (lastHeartbeatMs == 0L) return false
            return (android.os.SystemClock.elapsedRealtime() - lastHeartbeatMs) < maxStaleMs
        }

        private fun recordHeartbeat() {
            lastHeartbeatMs = android.os.SystemClock.elapsedRealtime()
        }

        internal fun rememberProgrammaticReplyCancel(
            sourceKey: String,
            mirrorKey: String,
            mirrorNotificationId: Int
        ) {
            rememberProgrammaticCancelKeys(
                sourceKey,
                mirrorKey,
                mirrorNotificationIdKey(mirrorNotificationId)
            )
        }

        internal fun rememberProgrammaticCancelKeys(vararg keys: String) {
            val now = SystemClock.elapsedRealtime()
            pruneProgrammaticCancelKeys(now)
            val expiresAtMs = now + PROGRAMMATIC_CANCEL_TTL_MS
            keys
                .map { key -> key.trim() }
                .filter { key -> key.isNotBlank() }
                .forEach { key ->
                    programmaticCancelDeadlines[key] = expiresAtMs
                }
        }

        private fun consumeProgrammaticCancelKeys(keys: Collection<String>): Boolean {
            val now = SystemClock.elapsedRealtime()
            pruneProgrammaticCancelKeys(now)
            keys
                .map { key -> key.trim() }
                .filter { key -> key.isNotBlank() }
                .forEach { key ->
                    val expiresAtMs = programmaticCancelDeadlines.remove(key)
                    if (expiresAtMs != null && expiresAtMs >= now) {
                        return true
                    }
                }
            return false
        }

        private fun pruneProgrammaticCancelKeys(now: Long) {
            programmaticCancelDeadlines
                .filterValues { expiresAtMs -> expiresAtMs < now }
                .keys
                .toList()
                .forEach(programmaticCancelDeadlines::remove)
        }

        private fun mirrorNotificationIdKey(notificationId: Int): String {
            return "mirror_id:$notificationId"
        }

        private fun notificationIdentityKey(
            packageName: String,
            notificationId: Int,
            tag: String?
        ): String {
            return "identity:$packageName:$notificationId:${tag.orEmpty()}"
        }

        fun requestFlashlightSnapshotSync() {
            activeInstance?.requestImmediateFlashlightSnapshotSync()
        }

        fun requestSnapshotSync() {
            activeInstance?.requestImmediateSnapshotSync()
        }

        fun requestChargingInfoSync() {
            activeInstance?.requestImmediateChargingInfoSync()
        }

        fun requestClearPackageNotifications(packageName: String) {
            val listener = activeInstance
            if (listener == null) {
                Log.w(TAG, "Skip capsule clear: listener is not active")
                return
            }
            listener.clearNotificationsForPackage(packageName)
        }

        fun requestClearNotificationKeys(notificationKeys: Collection<String>) {
            val listener = activeInstance
            if (listener == null) {
                Log.w(TAG, "Skip capsule clear: listener is not active")
                return
            }
            listener.clearNotificationsForKeys(notificationKeys)
        }

        fun requestFlashlightSourceDismissal() {
            val listener = activeInstance
            if (listener == null) {
                Log.w(TAG, "Skip flashlight source dismiss: listener is not active")
                return
            }
            listener.requestTrackedFlashlightSourceDismissal()
        }

        fun requestRebindIfEnabled(context: Context, reason: String): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                return false
            }
            if (!isListenerEnabled(context)) {
                Log.w(TAG, "Skip rebind ($reason): listener disabled")
                return false
            }

            return try {
                requestRebind(ComponentName(context, LiveUpdateNotificationListenerService::class.java))
                Log.i(TAG, "Requested listener rebind ($reason)")
                true
            } catch (error: Throwable) {
                Log.e(TAG, "Failed listener rebind ($reason)", error)
                false
            }
        }

        fun requestCancelSourceNotification(
            context: Context,
            sourceKey: String
        ) {
            val normalizedSourceKey = sourceKey.trim()
            if (normalizedSourceKey.isBlank()) {
                return
            }
            val target = ReplySourceCancelTarget(
                sourceKey = normalizedSourceKey,
                expiresAtMs = SystemClock.elapsedRealtime() + REPLY_SOURCE_CANCEL_TTL_MS
            )
            val listener = activeInstance
            if (listener == null) {
                rememberPendingReplySourceCancel(target)
                Log.w(TAG, "Queued source cancel after reply: listener is not active")
                requestRebindIfEnabled(context.applicationContext, "reply_source_cancel")
                return
            }
            try {
                val requested = listener.cancelReplySourceNotifications(target)
                if (requested) {
                    Log.i(TAG, "Requested original source notification cancel after reply: $normalizedSourceKey")
                } else {
                    rememberPendingReplySourceCancel(target)
                    Log.w(TAG, "Queued source cancel after reply: source not active yet $normalizedSourceKey")
                    listener.mainHandler.postDelayed(
                        { listener.drainPendingReplySourceCancels() },
                        ORIGINAL_DISMISS_RETRY_DELAY_MS
                    )
                }
            } catch (error: Throwable) {
                rememberPendingReplySourceCancel(target)
                Log.w(TAG, "Failed to cancel original source notification: $normalizedSourceKey", error)
            }
        }

        private fun rememberPendingReplySourceCancel(target: ReplySourceCancelTarget) {
            val now = SystemClock.elapsedRealtime()
            if (target.expiresAtMs < now) {
                return
            }
            synchronized(pendingReplySourceCancelLock) {
                prunePendingReplySourceCancelsLocked(now)
                pendingReplySourceCancels[target.sourceKey] = target
            }
        }

        private fun takePendingReplySourceCancels(): List<ReplySourceCancelTarget> {
            val now = SystemClock.elapsedRealtime()
            return synchronized(pendingReplySourceCancelLock) {
                prunePendingReplySourceCancelsLocked(now)
                val targets = pendingReplySourceCancels.values.toList()
                pendingReplySourceCancels.clear()
                targets
            }
        }

        private fun prunePendingReplySourceCancelsLocked(now: Long) {
            val expiredKeys = pendingReplySourceCancels
                .filterValues { target -> target.expiresAtMs < now }
                .keys
                .toList()
            expiredKeys.forEach(pendingReplySourceCancels::remove)
        }

        fun isListenerEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            ) ?: return false
            val service = ComponentName(context, LiveUpdateNotificationListenerService::class.java)
            return enabled.split(":")
                .mapNotNull(ComponentName::unflattenFromString)
                .any { it == service }
        }
    }
}
