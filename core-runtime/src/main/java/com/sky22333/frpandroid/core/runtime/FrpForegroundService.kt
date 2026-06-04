package com.sky22333.frpandroid.core.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.sky22333.frpandroid.core.data.AppGraph
import com.sky22333.frpandroid.core.frp.FrpInstanceStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class FrpForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val repository by lazy { AppGraph.repository(this) }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_PROFILE -> {
                val id = intent.getStringExtra(EXTRA_PROFILE_ID)
                if (id != null) startProfile(id)
            }
            ACTION_RECOVER_PROFILE -> {
                val id = intent.getStringExtra(EXTRA_PROFILE_ID)
                val attempt = intent.getIntExtra(EXTRA_RECOVERY_ATTEMPT, 0)
                val reason = intent.getStringExtra(EXTRA_RECOVERY_REASON)?.let { value ->
                    runCatching { RecoveryReason.valueOf(value) }.getOrNull()
                }
                if (id != null && reason != null) recoverProfile(id, attempt, reason)
            }
            ACTION_STOP_PROFILE -> {
                val id = intent.getStringExtra(EXTRA_PROFILE_ID)
                if (id != null) stopProfile(id)
            }
            ACTION_STOP_ALL -> stopAll()
            ACTION_SYNC -> syncOnly()
            else -> recoverKnownProfiles()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun startProfile(id: String) {
        scope.launch {
            if (!hasNotificationPermission()) {
                repository.setPendingStart(true)
                stopSelf()
                return@launch
            }
            startForeground(NOTIFICATION_ID, buildNotification(runningCount = 1))
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

    private fun recoverProfile(id: String, attempt: Int, reason: RecoveryReason) {
        scope.launch {
            if (!hasNotificationPermission()) {
                repository.setPendingStart(true)
                FrpRetryWorker.cancel(this@FrpForegroundService, id)
                stopSelf()
                return@launch
            }
            startForeground(NOTIFICATION_ID, buildNotification(runningCount = 1))
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

    private fun syncOnly() {
        scope.launch {
            repository.initialize()
            refreshNotificationOrStop()
        }
    }

    private fun recoverKnownProfiles() {
        scope.launch {
            val profiles = repository.getNetworkRecoverableProfiles()
            if (profiles.isEmpty()) {
                refreshNotificationOrStop()
                return@launch
            }
            profiles.forEach { profile ->
                recoverProfile(profile.id, attempt = 0, reason = RecoveryReason.Network)
            }
        }
    }

    private suspend fun refreshNotificationOrStop() {
        val count = repository.runtimeStates.first().count {
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

    private fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun pendingIntentFlags(): Int =
        PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0

    companion object {
        const val CHANNEL_ID = "frp_runtime"
        const val NOTIFICATION_ID = 22333
        const val ACTION_START_PROFILE = "com.sky22333.frpandroid.START_PROFILE"
        const val ACTION_RECOVER_PROFILE = "com.sky22333.frpandroid.RECOVER_PROFILE"
        const val ACTION_STOP_PROFILE = "com.sky22333.frpandroid.STOP_PROFILE"
        const val ACTION_STOP_ALL = "com.sky22333.frpandroid.STOP_ALL"
        const val ACTION_SYNC = "com.sky22333.frpandroid.SYNC"
        const val EXTRA_PROFILE_ID = "profile_id"
        const val EXTRA_RECOVERY_ATTEMPT = "recovery_attempt"
        const val EXTRA_RECOVERY_REASON = "recovery_reason"
        const val EXTRA_START_DESTINATION = "start_destination"
        const val DESTINATION_LOGS = "logs"

        fun startProfile(context: Context, profileId: String) {
            val intent = Intent(context, FrpForegroundService::class.java)
                .setAction(ACTION_START_PROFILE)
                .putExtra(EXTRA_PROFILE_ID, profileId)
            ContextCompat.startForegroundService(context, intent)
        }

        fun recoverProfile(context: Context, profileId: String, attempt: Int, reason: RecoveryReason) {
            val intent = Intent(context, FrpForegroundService::class.java)
                .setAction(ACTION_RECOVER_PROFILE)
                .putExtra(EXTRA_PROFILE_ID, profileId)
                .putExtra(EXTRA_RECOVERY_ATTEMPT, attempt)
                .putExtra(EXTRA_RECOVERY_REASON, reason.name)
            ContextCompat.startForegroundService(context, intent)
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

        fun sync(context: Context) {
            context.startService(Intent(context, FrpForegroundService::class.java).setAction(ACTION_SYNC))
        }
    }
}
