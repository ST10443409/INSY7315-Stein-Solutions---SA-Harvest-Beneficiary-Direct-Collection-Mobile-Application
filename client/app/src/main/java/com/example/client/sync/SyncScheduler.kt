package com.example.client.sync

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** What the UI needs from the scheduler: ask for a CBO sync soon. A separate type so ViewModels can be tested without WorkManager. */
interface CboSyncTrigger {
    fun syncCboCollectionsNow()
}

/**
 * Schedules the sync workers. Every request needs a connected network, so work queued while
 * offline simply waits and runs when connectivity returns, with no explicit connectivity listener.
 * The constraint, backoff and unique-work setup are shared so the Vetting worker can reuse them.
 */
@Singleton
class SyncScheduler @Inject constructor(
    private val workManager: WorkManager
) : CboSyncTrigger {
    /**
     * Sync CBO collections as soon as possible: call after a save and when the app comes to the
     * foreground. Calls made while a run is queued or running are chained after it, so a record
     * saved mid-run is still picked up, and rapid saves cannot start overlapping runs.
     */
    override fun syncCboCollectionsNow() {
        val request = OneTimeWorkRequestBuilder<CboSyncWorker>()
            .setConstraints(CONSTRAINTS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniqueWork(CBO_SYNC_NOW, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    /** Safety net: re-checks for unsynced records every [PERIOD_MINUTES] minutes. Safe to call on every launch. */
    fun scheduleCboCollectionsPeriodic() {
        val request = PeriodicWorkRequestBuilder<CboSyncWorker>(PERIOD_MINUTES, TimeUnit.MINUTES)
            .setConstraints(CONSTRAINTS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniquePeriodicWork(CBO_SYNC_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    companion object {
        const val CBO_SYNC_NOW = "cbo_sync_now"
        const val CBO_SYNC_PERIODIC = "cbo_sync_periodic"
        private const val PERIOD_MINUTES = 15L // WorkManager's minimum
        private const val BACKOFF_SECONDS = 30L

        val CONSTRAINTS: Constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
    }
}
