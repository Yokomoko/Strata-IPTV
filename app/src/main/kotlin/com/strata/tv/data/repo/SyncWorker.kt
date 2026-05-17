package com.strata.tv.data.repo

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.strata.tv.data.settings.SettingsRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlin.coroutines.cancellation.CancellationException

/**
 * WorkManager [CoroutineWorker] that runs an M3U playlist sync in
 * the background.  Reads the playlist URL from [SettingsRepository]
 * so a credentials change in Settings is picked up on the next run.
 *
 * Skips work cleanly if the user hasn't completed first-run setup
 * (provider not configured yet) — better than a hard failure that
 * burns retries.
 */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val syncService: SyncService,
    private val bootstrap: BootstrapRepository,
    private val settings: SettingsRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            val config = settings.current().provider
            if (!config.isConfigured) {
                Log.d(TAG, "Provider not configured; skipping background sync")
                return Result.success()
            }
            val sourceId = bootstrap.ensureSource()
            syncService.syncFromUrl(config.toM3uUrl(), sourceId)
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
