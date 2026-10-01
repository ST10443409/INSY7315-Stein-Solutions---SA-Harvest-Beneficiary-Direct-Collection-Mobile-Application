package com.example.client.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.io.IOException

enum class SyncRunResult { DONE, RETRY_LATER }

/**
 * Shared retry/backoff handling for the sync workers (CBO collections now, Vetting decisions
 * next). A subclass only says how to sync; this class decides how a run ends:
 * - [SyncRunResult.RETRY_LATER], or a network error, retries with WorkManager's backoff, up to [MAX_ATTEMPTS];
 * - after that the run ends as a failure, and the periodic schedule picks the work up again later.
 */
abstract class BaseSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    protected abstract suspend fun performSync(): SyncRunResult

    final override suspend fun doWork(): Result {
        val outcome = try {
            performSync()
        } catch (e: IOException) {
            SyncRunResult.RETRY_LATER
        }
        return when (outcome) {
            SyncRunResult.DONE -> Result.success()
            SyncRunResult.RETRY_LATER ->
                if (runAttemptCount + 1 >= MAX_ATTEMPTS) Result.failure() else Result.retry()
        }
    }

    companion object {
        const val MAX_ATTEMPTS = 5
    }
}
