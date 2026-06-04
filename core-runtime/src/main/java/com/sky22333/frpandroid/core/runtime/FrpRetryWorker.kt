package com.sky22333.frpandroid.core.runtime

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.sky22333.frpandroid.core.data.AppGraph
import java.util.concurrent.TimeUnit

class FrpRetryWorker(
    context: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(context, workerParameters) {
    override suspend fun doWork(): Result {
        val profileId = inputData.getString(KEY_PROFILE_ID) ?: return Result.failure()
        val attempt = inputData.getInt(KEY_ATTEMPT, 1)
        val reason = inputData.getString(KEY_REASON)?.let { value ->
            runCatching { RecoveryReason.valueOf(value) }.getOrNull()
        } ?: return Result.failure()
        val repository = AppGraph.repository(applicationContext)
        val enabled = when (reason) {
            RecoveryReason.AutoRetry -> repository.shouldAutoRetryFailures()
            RecoveryReason.Network -> repository.shouldReconnectOnNetworkRecovery()
        }
        if (!enabled || repository.getProfile(profileId) == null) return Result.success()
        return runCatching {
            FrpForegroundService.recoverProfile(applicationContext, profileId, attempt, reason)
            Result.success()
        }.getOrElse {
            enqueue(applicationContext, profileId, attempt + 1, reason)
            Result.success()
        }
    }

    companion object {
        private const val KEY_PROFILE_ID = "profile_id"
        private const val KEY_ATTEMPT = "attempt"
        private const val KEY_REASON = "reason"
        private const val TAG_RECOVERY = "frp_recovery"

        fun enqueue(context: Context, profileId: String, attempt: Int, reason: RecoveryReason) {
            val request = OneTimeWorkRequestBuilder<FrpRetryWorker>()
                .setInputData(
                    workDataOf(
                        KEY_PROFILE_ID to profileId,
                        KEY_ATTEMPT to attempt,
                        KEY_REASON to reason.name,
                    ),
                )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setInitialDelay(FrpRuntimePolicy.retryDelaySeconds(attempt), TimeUnit.SECONDS)
                .addTag(TAG_RECOVERY)
                .addTag(reasonTag(reason))
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                uniqueWorkName(profileId),
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }

        fun cancel(context: Context, profileId: String) {
            WorkManager.getInstance(context).cancelUniqueWork(uniqueWorkName(profileId))
        }

        fun cancelAll(context: Context) {
            WorkManager.getInstance(context).cancelAllWorkByTag(TAG_RECOVERY)
        }

        fun cancelAll(context: Context, reason: RecoveryReason) {
            WorkManager.getInstance(context).cancelAllWorkByTag(reasonTag(reason))
        }

        private fun uniqueWorkName(profileId: String): String = "frp_recovery_$profileId"
        private fun reasonTag(reason: RecoveryReason): String = "frp_recovery_${reason.name}"
    }
}

enum class RecoveryReason {
    AutoRetry,
    Network,
}
