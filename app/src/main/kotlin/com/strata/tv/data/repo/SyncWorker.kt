package com.strata.tv.data.repo

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.strata.tv.AppConfig
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlin.coroutines.cancellation.CancellationException

/**
 * WorkManager [CoroutineWorker] that runs an M3U playlist sync in
 * the background.  Scheduled periodically (every 12 hours) by
 * [HomeViewModel] so the library stays fresh without blocking
 * the every-launch path.
 *
 * Wired into Hilt via [@HiltWorker] so it can inject [SyncService]
 * and [BootstrapRepository] directly.
 */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val syncService: SyncService,
    private val bootstrap: BootstrapRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            val sourceId = bootstrap.ensureSource()
            syncService.syncFromUrl(AppConfig.PLAYLIST_URL, sourceId)
            Log.d(TAG, "Background sync completed successfully")
            Result.success()
        } catch (e: CancellationException) {
            throw e  // propagate cancellation cooperatively
        } catch (e: Exception) {
            Log.w(TAG, "Background sync failed", e)
            // Retry on transient failures (network errors etc.).
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val TAG = "SyncWorker"
        const val WORK_NAME = "strata_periodic_sync"
    }
}
