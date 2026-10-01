package com.example.client.sync

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
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

/** What the UI needs from the scheduler: ask for a vetting decisions sync soon. See [CboSyncTrigger]. */
interface VettingSyncTrigger {
    fun syncVettingDecisionsNow()
}

/**
 * Schedules the sync workers. Every request needs a connected network, so work queued while
 * offline simply waits and runs when connectivity returns, with no explicit connectivity listener.
 * The constraint, backoff and unique-work setup are shared by every worker (below), so each kind of
 * record is scheduled identically.
 */
@Singleton
class SyncScheduler @Inject constructor(
    private val workManager: WorkManager
) : CboSyncTrigger, VettingSyncTrigger {
    /**
     * Sync CBO collections as soon as possible: call after a save and when the app comes to the
     * foreground. Calls made while a run is queued or running are chained after it, so a record
     * saved mid-run is still picked up, and rapid saves cannot start overlapping runs.
     */
    override fun syncCboCollectionsNow() = enqueueNow<CboSyncWorker>(CBO_SYNC_NOW)

    /** The same for vetting decisions: call after a decision is saved and when the app comes to the foreground. */
    override fun syncVettingDecisionsNow() = enqueueNow<VettingSyncWorker>(VETTING_SYNC_NOW)

    /** Safety net: re-checks for unsynced records every [PERIOD_MINUTES] minutes. Safe to call on every launch. */
    fun scheduleCboCollectionsPeriodic() = schedulePeriodic<CboSyncWorker>(CBO_SYNC_PERIODIC)

    /** The same safety net for vetting decisions. Safe to call on every launch. */
    fun scheduleVettingDecisionsPeriodic() = schedulePeriodic<VettingSyncWorker>(VETTING_SYNC_PERIODIC)

    private inline fun <reified W : ListenableWorker> enqueueNow(name: String) {
        val request = OneTimeWorkRequestBuilder<W>()
            .setConstraints(CONSTRAINTS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniqueWork(name, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    private inline fun <reified W : ListenableWorker> schedulePeriodic(name: String) {
        val request = PeriodicWorkRequestBuilder<W>(PERIOD_MINUTES, TimeUnit.MINUTES)
            .setConstraints(CONSTRAINTS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniquePeriodicWork(name, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    companion object {
        const val CBO_SYNC_NOW = "cbo_sync_now"
        const val CBO_SYNC_PERIODIC = "cbo_sync_periodic"
        const val VETTING_SYNC_NOW = "vetting_sync_now"
        const val VETTING_SYNC_PERIODIC = "vetting_sync_periodic"
        private const val PERIOD_MINUTES = 15L // WorkManager's minimum
        private const val BACKOFF_SECONDS = 30L

        val CONSTRAINTS: Constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
    }
}
