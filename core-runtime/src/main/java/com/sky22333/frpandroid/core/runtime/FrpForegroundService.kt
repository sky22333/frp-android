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
        scope.launch { repository.initialize() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_PROFILE -> {
                val id = intent.getStringExtra(EXTRA_PROFILE_ID)
                if (id != null) startProfile(id)
            }
            ACTION_STOP_PROFILE -> {
                val id = intent.getStringExtra(EXTRA_PROFILE_ID)
                if (id != null) stopProfile(id)
            }
            ACTION_STOP_ALL -> stopAll()
            ACTION_SYNC -> syncOnly()
            else -> syncOnly()
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
            repository.initialize()
            repository.getProfile(id)?.let { profile ->
                val result = repository.start(profile)
                if (FrpRuntimePolicy.isStartSatisfied(result)) {
                    repository.setPendingStart(false)
                }
                if (FrpRuntimePolicy.shouldRetryStart(result, repository.shouldAutoRetryFailures())) {
                    FrpRetryWorker.enqueue(this@FrpForegroundService, profile.id)
                }
            }
            refreshNotificationOrStop()
        }
    }

    private fun stopProfile(id: String) {
        scope.launch {
            repository.getProfile(id)?.let { repository.stop(it) }
            refreshNotificationOrStop()
        }
    }

    private fun stopAll() {
        scope.launch {
            repository.stopAll()
            ServiceCompat.stopForeground(this@FrpForegroundService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun syncOnly() {
        scope.launch {
            repository.initialize()
            refreshNotificationOrStop()
        }
    }

    private suspend fun refreshNotificationOrStop() {
        val count = repository.runtimeStates.first().count { it.state == FrpInstanceStatus.Running }
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
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
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
        const val ACTION_STOP_PROFILE = "com.sky22333.frpandroid.STOP_PROFILE"
        const val ACTION_STOP_ALL = "com.sky22333.frpandroid.STOP_ALL"
        const val ACTION_SYNC = "com.sky22333.frpandroid.SYNC"
        const val EXTRA_PROFILE_ID = "profile_id"
        const val EXTRA_START_DESTINATION = "start_destination"
        const val DESTINATION_LOGS = "logs"

        fun startProfile(context: Context, profileId: String) {
            val intent = Intent(context, FrpForegroundService::class.java)
                .setAction(ACTION_START_PROFILE)
                .putExtra(EXTRA_PROFILE_ID, profileId)
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
