package com.sky22333.frpandroid.core.runtime

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.sky22333.frpandroid.core.data.AppGraph
import com.sky22333.frpandroid.core.frp.FrpInstanceStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class FrpForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val repository by lazy { AppGraph.repository(this) }
    private val keepAliveLock = Any()
    @Volatile
    private var keepAliveMonitoring = false
    @Volatile
    private var screenOffKeepAliveEnabled = false
    @Volatile
    private var hasActiveInstances = false
    @Volatile
    private var screenOff = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var screenReceiver: BroadcastReceiver? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startScreenOffKeepAliveMonitoring()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_PROFILE -> {
                val id = intent.getStringExtra(EXTRA_PROFILE_ID) ?: return START_NOT_STICKY
                if (!promoteToForegroundOrAbort()) return START_NOT_STICKY
                startProfile(id)
            }
            ACTION_RESTART_PROFILE -> {
                val id = intent.getStringExtra(EXTRA_PROFILE_ID) ?: return START_NOT_STICKY
                if (!promoteToForegroundOrAbort()) return START_NOT_STICKY
                restartProfile(id)
            }
            ACTION_RECOVER_PROFILE -> {
                val id = intent.getStringExtra(EXTRA_PROFILE_ID) ?: return START_NOT_STICKY
                val attempt = intent.getIntExtra(EXTRA_RECOVERY_ATTEMPT, 0)
                val reason = intent.getStringExtra(EXTRA_RECOVERY_REASON)?.let { value ->
                    runCatching { RecoveryReason.valueOf(value) }.getOrNull()
                } ?: return START_NOT_STICKY
                if (!promoteToForegroundOrAbort()) return START_NOT_STICKY
                recoverProfile(id, attempt, reason)
            }
            ACTION_STOP_PROFILE -> {
                val id = intent.getStringExtra(EXTRA_PROFILE_ID)
                if (id != null) stopProfile(id)
            }
            ACTION_STOP_ALL -> stopAll()
            ACTION_RESTORE_DESIRED -> {
                if (!promoteToForegroundOrAbort()) return START_NOT_STICKY
                restoreDesired()
            }
            ACTION_MARK_PENDING_START -> {
                scope.launch {
                    repository.setPendingStart(true)
                    stopSelf()
                }
            }
            else -> {
                if (!promoteToForegroundOrAbort()) return START_NOT_STICKY
                restoreDesired()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopScreenOffKeepAliveMonitoring()
        scope.cancel()
        super.onDestroy()
    }

    private fun startProfile(id: String) {
        scope.launch {
            FrpRetryWorker.cancel(this@FrpForegroundService, id)
            repository.getProfile(id)?.let { profile ->
                val result = repository.start(profile)
                if (FrpRuntimePolicy.isStartSatisfied(result)) {
                    FrpRetryWorker.cancel(this@FrpForegroundService, profile.id)
                    repository.setPendingStart(false)
                }
                if (FrpRuntimePolicy.shouldRetryStart(result, repository.shouldAutoRetryFailures())) {
                    FrpRetryWorker.enqueue(
                        this@FrpForegroundService,
                        profile.id,
                        attempt = 1,
                        reason = RecoveryReason.AutoRetry,
                    )
                }
            }
            refreshNotificationOrStop()
        }
    }

    private fun restartProfile(id: String) {
        scope.launch {
            FrpRetryWorker.cancel(this@FrpForegroundService, id)
            val profile = repository.getProfile(id) ?: run {
                refreshNotificationOrStop()
                return@launch
            }
            val stopResult = repository.stop(profile)
            if (stopResult.isSuccess || stopResult.isAlreadyStopped) {
                val result = repository.start(profile)
                if (FrpRuntimePolicy.isStartSatisfied(result)) {
                    FrpRetryWorker.cancel(this@FrpForegroundService, profile.id)
                    repository.setPendingStart(false)
                }
                if (FrpRuntimePolicy.shouldRetryStart(result, repository.shouldAutoRetryFailures())) {
                    FrpRetryWorker.enqueue(
                        this@FrpForegroundService,
                        profile.id,
                        attempt = 1,
                        reason = RecoveryReason.AutoRetry,
                    )
                }
            }
            refreshNotificationOrStop()
        }
    }

    private fun recoverProfile(id: String, attempt: Int, reason: RecoveryReason) {
        scope.launch {
            val result = repository.recoverProfile(id)
            if (result == null || FrpRuntimePolicy.isStartSatisfied(result)) {
                FrpRetryWorker.cancel(this@FrpForegroundService, id)
            } else {
                val enabled = when (reason) {
                    RecoveryReason.AutoRetry -> repository.shouldAutoRetryFailures()
                    RecoveryReason.Network -> repository.shouldReconnectOnNetworkRecovery()
                }
                if (FrpRuntimePolicy.shouldRetryStart(result, enabled)) {
                    FrpRetryWorker.enqueue(this@FrpForegroundService, id, attempt + 1, reason)
                } else {
                    FrpRetryWorker.cancel(this@FrpForegroundService, id)
                }
            }
            refreshNotificationOrStop()
        }
    }

    private fun stopProfile(id: String) {
        scope.launch {
            FrpRetryWorker.cancel(this@FrpForegroundService, id)
            repository.getProfile(id)?.let { repository.stop(it) }
            refreshNotificationOrStop()
        }
    }

    private fun stopAll() {
        scope.launch {
            FrpRetryWorker.cancelAll(this@FrpForegroundService)
            repository.stopAll()
            refreshNotificationOrStop()
        }
    }

    private fun restoreDesired() {
        scope.launch {
            repository.restoreDesiredProfiles()
            refreshNotificationOrStop()
        }
    }

    private suspend fun refreshNotificationOrStop() {
        val states = repository.runtimeStates.first()
        val count = states.count {
            it.state == FrpInstanceStatus.Running || it.state == FrpInstanceStatus.Stopping
        }
        if (count <= 0) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(count))
    }

    private fun startScreenOffKeepAliveMonitoring() {
        keepAliveMonitoring = true

        screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> screenOff = true
                    Intent.ACTION_SCREEN_ON -> screenOff = false
                    else -> return
                }
                updateScreenOffKeepAlive()
            }
        }.also { receiver ->
            ContextCompat.registerReceiver(
                this,
                receiver,
                IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(Intent.ACTION_SCREEN_ON)
                },
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }
        screenOff = !getSystemService(PowerManager::class.java).isInteractive

        val connectivityManager = getSystemService(ConnectivityManager::class.java)
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = updateScreenOffKeepAlive()
            override fun onLost(network: Network) = updateScreenOffKeepAlive()
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) =
                updateScreenOffKeepAlive()
        }.also { callback ->
            connectivityManager.registerDefaultNetworkCallback(callback)
        }

        scope.launch {
            repository.settings.collect { settings ->
                screenOffKeepAliveEnabled = settings.screenOffKeepAliveEnabled
                updateScreenOffKeepAlive()
            }
        }
        scope.launch {
            repository.runtimeStates.collect { states ->
                hasActiveInstances = states.any {
                    it.state == FrpInstanceStatus.Running || it.state == FrpInstanceStatus.Stopping
                }
                updateScreenOffKeepAlive()
            }
        }
    }

    private fun stopScreenOffKeepAliveMonitoring() {
        keepAliveMonitoring = false
        screenReceiver?.let { receiver -> runCatching { unregisterReceiver(receiver) } }
        screenReceiver = null
        networkCallback?.let { callback ->
            runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(callback) }
        }
        networkCallback = null
        synchronized(keepAliveLock) {
            releaseScreenOffKeepAliveLocked()
        }
    }

    @SuppressLint("WakelockTimeout")
    private fun updateScreenOffKeepAlive() {
        synchronized(keepAliveLock) {
            if (!keepAliveMonitoring) {
                releaseScreenOffKeepAliveLocked()
                return
            }
            val shouldKeepAlive = FrpRuntimePolicy.shouldHoldScreenOffKeepAlive(
                enabled = screenOffKeepAliveEnabled,
                screenOff = screenOff,
                hasActiveInstances = hasActiveInstances,
            )
            if (shouldKeepAlive) {
                acquireWakeLockLocked()
            } else {
                releaseWakeLockLocked()
            }

            if (FrpRuntimePolicy.shouldHoldWifiLock(shouldKeepAlive, isDefaultNetworkWifi())) {
                acquireWifiLockLocked()
            } else {
                releaseWifiLockLocked()
            }
        }
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLockLocked() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:screen-off-keep-alive")
            .apply {
                setReferenceCounted(false)
                acquire()
            }
    }

    @SuppressLint("WakelockTimeout")
    @Suppress("DEPRECATION")
    private fun acquireWifiLockLocked() {
        if (wifiLock?.isHeld == true) return
        wifiLock = applicationContext.getSystemService(WifiManager::class.java)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "$packageName:screen-off-keep-alive")
            .apply {
                setReferenceCounted(false)
                acquire()
            }
    }

    private fun releaseScreenOffKeepAliveLocked() {
        releaseWifiLockLocked()
        releaseWakeLockLocked()
    }

    private fun releaseWakeLockLocked() {
        wakeLock?.let { lock ->
            if (lock.isHeld) lock.release()
        }
        wakeLock = null
    }

    private fun releaseWifiLockLocked() {
        wifiLock?.let { lock ->
            if (lock.isHeld) lock.release()
        }
        wifiLock = null
    }

    private fun isDefaultNetworkWifi(): Boolean {
        val manager = getSystemService(ConnectivityManager::class.java)
        val network = manager.activeNetwork ?: return false
        return manager.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }

    private fun buildNotification(runningCount: Int): Notification {
        val openAppIntent = packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
            PendingIntent.getActivity(this, 0, launch, pendingIntentFlags())
        }
        val stopAllIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, FrpForegroundService::class.java).setAction(ACTION_STOP_ALL),
            pendingIntentFlags(),
        )
        val logsIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            putExtra(EXTRA_START_DESTINATION, DESTINATION_LOGS)
        }?.let { launch ->
            PendingIntent.getActivity(this, 2, launch, pendingIntentFlags())
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_frp)
            .setContentTitle(getString(R.string.notification_running_title))
            .setContentText(getString(R.string.notification_running_content, runningCount))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, getString(R.string.notification_stop_all), stopAllIntent)

        if (openAppIntent != null) {
            builder.setContentIntent(openAppIntent)
            builder.addAction(0, getString(R.string.notification_open_app), openAppIntent)
        }
        if (logsIntent != null) {
            builder.addAction(0, getString(R.string.notification_view_logs), logsIntent)
        }
        return builder.build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun promoteToForegroundOrAbort(): Boolean {
        // Must run before returning from onStartCommand when started via startForegroundService.
        promoteToForeground()
        if (!FrpRuntimePermissions.hasNotificationPermission(this)) {
            scope.launch {
                repository.setPendingStart(true)
                ServiceCompat.stopForeground(this@FrpForegroundService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            return false
        }
        return true
    }

    private fun promoteToForeground() {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(runningCount = 1),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
    }

    private fun pendingIntentFlags(): Int =
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    companion object {
        const val CHANNEL_ID = "frp_runtime"
        const val NOTIFICATION_ID = 22333
        const val ACTION_START_PROFILE = "com.sky22333.frpandroid.START_PROFILE"
        const val ACTION_RESTART_PROFILE = "com.sky22333.frpandroid.RESTART_PROFILE"
        const val ACTION_RECOVER_PROFILE = "com.sky22333.frpandroid.RECOVER_PROFILE"
        const val ACTION_STOP_PROFILE = "com.sky22333.frpandroid.STOP_PROFILE"
        const val ACTION_STOP_ALL = "com.sky22333.frpandroid.STOP_ALL"
        const val ACTION_RESTORE_DESIRED = "com.sky22333.frpandroid.RESTORE_DESIRED"
        const val ACTION_MARK_PENDING_START = "com.sky22333.frpandroid.MARK_PENDING_START"
        const val EXTRA_PROFILE_ID = "profile_id"
        const val EXTRA_RECOVERY_ATTEMPT = "recovery_attempt"
        const val EXTRA_RECOVERY_REASON = "recovery_reason"
        const val EXTRA_START_DESTINATION = "start_destination"
        const val DESTINATION_LOGS = "logs"

        fun startProfile(context: Context, profileId: String) {
            val intent = Intent(context, FrpForegroundService::class.java)
                .setAction(ACTION_START_PROFILE)
                .putExtra(EXTRA_PROFILE_ID, profileId)
            startRuntimeService(context, intent)
        }

        fun restartProfile(context: Context, profileId: String) {
            val intent = Intent(context, FrpForegroundService::class.java)
                .setAction(ACTION_RESTART_PROFILE)
                .putExtra(EXTRA_PROFILE_ID, profileId)
            startRuntimeService(context, intent)
        }

        fun recoverProfile(context: Context, profileId: String, attempt: Int, reason: RecoveryReason) {
            val intent = Intent(context, FrpForegroundService::class.java)
                .setAction(ACTION_RECOVER_PROFILE)
                .putExtra(EXTRA_PROFILE_ID, profileId)
                .putExtra(EXTRA_RECOVERY_ATTEMPT, attempt)
                .putExtra(EXTRA_RECOVERY_REASON, reason.name)
            startRuntimeService(context, intent)
        }

        fun stopProfile(context: Context, profileId: String) {
            context.startService(
                Intent(context, FrpForegroundService::class.java)
                    .setAction(ACTION_STOP_PROFILE)
                    .putExtra(EXTRA_PROFILE_ID, profileId),
            )
        }

        fun stopAll(context: Context) {
            context.startService(Intent(context, FrpForegroundService::class.java).setAction(ACTION_STOP_ALL))
        }

        fun restoreDesired(context: Context) {
            startRuntimeService(
                context,
                Intent(context, FrpForegroundService::class.java).setAction(ACTION_RESTORE_DESIRED),
            )
        }

        private fun startRuntimeService(context: Context, intent: Intent) {
            if (!FrpRuntimePermissions.hasNotificationPermission(context)) {
                context.startService(
                    Intent(context, FrpForegroundService::class.java).setAction(ACTION_MARK_PENDING_START),
                )
                return
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
