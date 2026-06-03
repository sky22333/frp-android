package com.sky22333.frpandroid.core.runtime

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.sky22333.frpandroid.core.data.AppGraph
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

class LogCleanupWorker(
    context: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(context, workerParameters) {
    override suspend fun doWork(): Result {
        val repository = AppGraph.repository(applicationContext)
        val settings = repository.settings.first()
        repository.pruneLogs(settings.logRetentionDays)
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "frp_log_cleanup"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<LogCleanupWorker>(1, TimeUnit.DAYS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }
    }
}
