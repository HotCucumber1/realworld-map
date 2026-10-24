package com.worldexplorer.data.sync

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.worldexplorer.domain.repository.SyncScheduler
import com.worldexplorer.domain.result.AppError
import com.worldexplorer.domain.result.AppResult
import com.worldexplorer.domain.usecase.SyncDataUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val syncData: SyncDataUseCase,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = when (val r = syncData()) {
        is AppResult.Success -> Result.success()
        is AppResult.Failure -> when (r.error) {
            // Transient: WorkManager retries with exponential backoff.
            AppError.NoNetwork, is AppError.BackendUnavailable -> if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
            else -> {
                Log.w("WorldExplorer", "background sync stopped: ${r.error}")
                Result.failure()
            }
        }
    }

    private companion object {
        const val MAX_ATTEMPTS = 5
    }
}

@Singleton
class WorkManagerSyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) : SyncScheduler {
    private val workManager get() = WorkManager.getInstance(context)

    override fun schedulePeriodic(unmeteredOnly: Boolean) {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(PERIOD_HOURS, TimeUnit.HOURS)
            .setConstraints(constraints(if (unmeteredOnly) NetworkType.UNMETERED else NetworkType.CONNECTED))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    override fun cancelPeriodic() {
        workManager.cancelUniqueWork(PERIODIC)
    }

    override fun requestSyncNow() {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints(NetworkType.CONNECTED))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniqueWork(ONE_TIME, ExistingWorkPolicy.KEEP, request)
    }

    private fun constraints(network: NetworkType) = Constraints.Builder()
        .setRequiredNetworkType(network)
        .setRequiresStorageNotLow(true)
        .build()

    private companion object {
        const val PERIODIC = "sync-periodic"
        const val ONE_TIME = "sync-now"
        const val PERIOD_HOURS = 6L
        const val BACKOFF_SECONDS = 30L
    }
}
