package com.sky22333.frpandroid.core.runtime

import android.content.Context
import androidx.work.BackoffPolicy
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
        val repository = AppGraph.repository(applicationContext)
        if (!repository.shouldAutoRetryFailures()) return Result.success()
        val profile = repository.getProfile(profileId) ?: return Result.failure()
        val result = repository.start(profile)
        return if (result.isSuccess) Result.success() else Result.retry()
    }

    companion object {
        private const val KEY_PROFILE_ID = "profile_id"

        fun enqueue(context: Context, profileId: String) {
            val request = OneTimeWorkRequestBuilder<FrpRetryWorker>()
                .setInputData(workDataOf(KEY_PROFILE_ID to profileId))
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                "frp_retry_$profileId",
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }
    }
}
