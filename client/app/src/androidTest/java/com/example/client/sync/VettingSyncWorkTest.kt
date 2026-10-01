package com.example.client.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.example.client.data.AppDatabase
import com.example.client.data.local.entity.SyncStatus
import com.example.client.network.ApiEnvelope
import com.example.client.network.CboSyncRecordResult
import com.example.client.network.CboSyncRequest
import com.example.client.network.CboSyncResponse
import com.example.client.network.SyncApiService
import com.example.client.network.SyncRequest
import com.example.client.network.SyncResponse
import com.example.client.network.VettingSyncRequest
import com.example.client.testing.sampleDecision
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.Response
import java.io.IOException

/**
 * The Vetting sync worker end to end on a device: a real Room database and the real processor, with only the network
 * faked. Also checks how it is scheduled, which is what `adb shell dumpsys jobscheduler` would show.
 */
@RunWith(AndroidJUnit4::class)
class VettingSyncWorkTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: AppDatabase

    private class FakeApi(var handler: (VettingSyncRequest) -> Response<ApiEnvelope<CboSyncResponse>>) : SyncApiService {
        val requests = mutableListOf<VettingSyncRequest>()
        override suspend fun syncData(request: SyncRequest): Response<SyncResponse> = error("unused")
        override suspend fun syncCboCollections(request: CboSyncRequest): Response<ApiEnvelope<CboSyncResponse>> = error("unused")
        override suspend fun syncVettingDecisions(request: VettingSyncRequest): Response<ApiEnvelope<CboSyncResponse>> {
            requests += request
            return handler(request)
        }
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    }

    @After
    fun tearDown() = db.close()

    private fun worker(api: SyncApiService, attempt: Int = 0): VettingSyncWorker =
        TestListenableWorkerBuilder<VettingSyncWorker>(context)
            .setRunAttemptCount(attempt)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker =
                    VettingSyncWorker(appContext, workerParameters, VettingSyncProcessor(db.vettingDecisionDao(), api))
            })
            .build()

    private fun ok(req: VettingSyncRequest) =
        Response.success(ApiEnvelope(success = true, data = CboSyncResponse(req.records.map { CboSyncRecordResult(it.id, true) })))

    // ── the worker ─────────────────────────────────────────────────────────────────

    @Test
    fun pendingDecisions_areSent_andMarkedSynced_inTheRealDatabase() = runBlocking {
        db.vettingDecisionDao().insert(sampleDecision("fs-1").copy(id = "d1"))
        db.vettingDecisionDao().insert(sampleDecision("fs-2").copy(id = "d2"))
        val api = FakeApi(::ok)

        val result = worker(api).doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(setOf("d1", "d2"), api.requests.single().records.map { it.id }.toSet())
        assertTrue(db.vettingDecisionDao().getAll().first().all { it.syncStatus == SyncStatus.SYNCED })
    }

    @Test
    fun withoutAConnection_theWorkerAsksToBeRetried_andNothingIsLost() = runBlocking {
        db.vettingDecisionDao().insert(sampleDecision("fs-1").copy(id = "d1"))

        val result = worker(FakeApi { throw IOException("offline") }).doWork()

        assertEquals(ListenableWorker.Result.retry(), result)
        val saved = db.vettingDecisionDao().getAll().first().single()
        assertEquals(SyncStatus.PENDING, saved.syncStatus)
        assertEquals(0, saved.retryCount)
    }

    @Test
    fun afterTooManyRetries_theWorkerGivesUp_untilThePeriodicRunPicksItUp_justLikeTheCollectionWorker() = runBlocking {
        db.vettingDecisionDao().insert(sampleDecision("fs-1").copy(id = "d1"))
        val offline = FakeApi { throw IOException("offline") }

        val last = worker(offline, attempt = BaseSyncWorker.MAX_ATTEMPTS - 1).doWork()

        assertEquals(ListenableWorker.Result.failure(), last)
        assertEquals(SyncStatus.PENDING, db.vettingDecisionDao().getAll().first().single().syncStatus) // still waiting
    }

    @Test
    fun aRejectedDecision_isRecorded_andTheWorkerDoesNotRetryForIt() = runBlocking {
        db.vettingDecisionDao().insert(sampleDecision("fs-1").copy(id = "d1"))
        val api = FakeApi {
            Response.success(ApiEnvelope(success = true, data = CboSyncResponse(listOf(CboSyncRecordResult("d1", false, errorCode = "VALIDATION_FAILED", retryable = false)))))
        }

        val result = worker(api).doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        val saved = db.vettingDecisionDao().getAll().first().single()
        assertEquals(SyncStatus.FAILED, saved.syncStatus)
        assertEquals("VALIDATION_FAILED", saved.syncErrorCode)
    }

    @Test
    fun bothWorkers_shareOneBase() {
        assertTrue(BaseSyncWorker::class.java.isAssignableFrom(VettingSyncWorker::class.java))
        assertTrue(BaseSyncWorker::class.java.isAssignableFrom(CboSyncWorker::class.java))
    }

    // ── how it is scheduled ────────────────────────────────────────────────────────

    private fun scheduler(): Pair<SyncScheduler, WorkManager> {
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().build())
        val manager = WorkManager.getInstance(context)
        return SyncScheduler(manager) to manager
    }

    @Test
    fun syncNow_queuesOneNetworkConstrainedJob_underItsOwnName() {
        val (scheduler, manager) = scheduler()

        scheduler.syncVettingDecisionsNow()

        val infos = manager.getWorkInfosForUniqueWork(SyncScheduler.VETTING_SYNC_NOW).get()
        assertEquals(1, infos.size)
        assertEquals(WorkInfo.State.ENQUEUED, infos.single().state)
        assertTrue(VETTING_WORKER_TAG in infos.single().tags)
        assertEquals(NetworkType.CONNECTED, SyncScheduler.CONSTRAINTS.requiredNetworkType)
        // The collections job is independent: asking for one does not queue the other.
        assertTrue(manager.getWorkInfosForUniqueWork(SyncScheduler.CBO_SYNC_NOW).get().isEmpty())
    }

    @Test
    fun rapidSaves_chainTheirSyncs_insteadOfRunningThemOnTopOfEachOther() {
        val (scheduler, manager) = scheduler()

        repeat(3) { scheduler.syncVettingDecisionsNow() }

        val infos = manager.getWorkInfosForUniqueWork(SyncScheduler.VETTING_SYNC_NOW).get()
        assertEquals(3, infos.size) // appended one after another (APPEND_OR_REPLACE), never overlapping
        assertEquals(1, infos.count { it.state == WorkInfo.State.ENQUEUED })
        assertEquals(2, infos.count { it.state == WorkInfo.State.BLOCKED })
    }

    @Test
    fun thePeriodicSafetyNet_isScheduledOnce_evenIfCalledOnEveryLaunch() {
        val (scheduler, manager) = scheduler()

        repeat(3) { scheduler.scheduleVettingDecisionsPeriodic() }

        assertEquals(1, manager.getWorkInfosForUniqueWork(SyncScheduler.VETTING_SYNC_PERIODIC).get().size)
    }

    private companion object {
        val VETTING_WORKER_TAG: String = VettingSyncWorker::class.java.name
    }
}
