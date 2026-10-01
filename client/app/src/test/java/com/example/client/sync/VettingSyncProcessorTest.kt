package com.example.client.sync

import com.example.client.data.local.entity.DecisionOutcome
import com.example.client.data.local.entity.SyncStatus
import com.example.client.network.ApiEnvelope
import com.example.client.network.CboSyncErrorCodes
import com.example.client.network.CboSyncRecordResult
import com.example.client.network.CboSyncRequest
import com.example.client.network.CboSyncResponse
import com.example.client.network.SyncApiService
import com.example.client.network.SyncRequest
import com.example.client.network.SyncResponse
import com.example.client.network.VettingSyncRequest
import com.example.client.testing.FakeVettingDecisionDao
import com.example.client.testing.sampleDecision
import com.google.gson.Gson
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import java.io.IOException

/** Vetting decisions are sent with exactly the rules Form 1 collections are (see [BatchSyncRunner]). */
class VettingSyncProcessorTest {

    internal class FakeApi(var handler: (VettingSyncRequest) -> Response<ApiEnvelope<CboSyncResponse>>) : SyncApiService {
        val requests = mutableListOf<VettingSyncRequest>()
        override suspend fun syncData(request: SyncRequest): Response<SyncResponse> = error("unused")
        override suspend fun syncCboCollections(request: CboSyncRequest): Response<ApiEnvelope<CboSyncResponse>> = error("unused")
        override suspend fun syncVettingDecisions(request: VettingSyncRequest): Response<ApiEnvelope<CboSyncResponse>> {
            requests += request
            return handler(request)
        }
    }

    private lateinit var dao: FakeVettingDecisionDao
    private lateinit var api: FakeApi
    private lateinit var processor: VettingSyncProcessor

    private fun decision(id: String, status: SyncStatus = SyncStatus.PENDING, retryCount: Int = 0, at: Long = 1_000) =
        sampleDecision(recordId = "fs-$id", at = at).copy(id = id, syncStatus = status, retryCount = retryCount)

    private fun ok(vararg results: CboSyncRecordResult) =
        Response.success(ApiEnvelope(success = true, data = CboSyncResponse(results.toList())))

    private fun okAll(req: VettingSyncRequest) = ok(*req.records.map { CboSyncRecordResult(it.id, true) }.toTypedArray())

    private fun httpError(code: Int): Response<ApiEnvelope<CboSyncResponse>> =
        Response.error(code, "{}".toResponseBody("application/json".toMediaType()))

    @Before
    fun setUp() {
        dao = FakeVettingDecisionDao()
        api = FakeApi { ok() }
        processor = VettingSyncProcessor(dao, api)
    }

    // ── what is sent ───────────────────────────────────────────────────────────────

    @Test
    fun pendingDecisions_areSentToTheVettingEndpoint_andBecomeSynced() = runTest {
        dao.rows.value = listOf(decision("a"), decision("b"))
        api.handler = ::okAll

        assertEquals(SyncRunResult.DONE, processor.syncPending())

        assertEquals(1, api.requests.size)
        assertEquals(listOf("a", "b"), api.requests.single().records.map { it.id })
        assertEquals(SyncStatus.SYNCED, dao.get("a").syncStatus)
        assertEquals(SyncStatus.SYNCED, dao.get("b").syncStatus)
    }

    @Test
    fun theRequest_carriesEveryFieldOfTheDecision_andOutcomesAsTheirWireNames() = runTest {
        dao.rows.value = listOf(
            decision("a").copy(outcome = DecisionOutcome.REJECT, notes = "no certificate", officerId = "vetting_test_user", decisionTimestamp = 123, createdAt = 100, updatedAt = 200)
        )
        api.handler = ::okAll

        processor.syncPending()

        val json = Gson().toJson(api.requests.single())
        assertEquals(
            """{"records":[{"id":"a","foodspaceRecordId":"fs-a","outcome":"REJECT","notes":"no certificate","officerId":"vetting_test_user","decisionTimestamp":123,"createdAt":100,"updatedAt":200}]}""",
            json
        )
    }

    @Test
    fun deviceOnlyBookkeeping_isNeverSent() = runTest {
        dao.rows.value = listOf(decision("a", SyncStatus.FAILED, retryCount = 2).copy(syncErrorCode = "SERVER_ERROR"))
        api.handler = ::okAll

        processor.syncPending()

        val json = Gson().toJson(api.requests.single())
        assertTrue("syncStatus" !in json && "retryCount" !in json && "syncErrorCode" !in json)
    }

    @Test
    fun theOldestDecisionsAreSentFirst() = runTest {
        dao.rows.value = listOf(decision("late", at = 3_000), decision("early", at = 1_000), decision("middle", at = 2_000))
        api.handler = ::okAll

        processor.syncPending()

        assertEquals(listOf("early", "middle", "late"), api.requests.single().records.map { it.id })
    }

    @Test
    fun moreThanOneBatch_isSentInBatches() = runTest {
        dao.rows.value = (1..(SyncPolicy.BATCH_SIZE + 5)).map { decision("d$it", at = it.toLong()) }
        api.handler = ::okAll

        processor.syncPending()

        assertEquals(listOf(SyncPolicy.BATCH_SIZE, 5), api.requests.map { it.records.size })
        assertTrue(dao.rows.value.all { it.syncStatus == SyncStatus.SYNCED })
    }

    @Test
    fun nothingToSync_makesNoRequest() = runTest {
        dao.rows.value = listOf(decision("done", SyncStatus.SYNCED))

        assertEquals(SyncRunResult.DONE, processor.syncPending())
        assertEquals(0, api.requests.size)
    }

    @Test
    fun anAlreadyReceivedDecision_countsAsSynced() = runTest {
        dao.rows.value = listOf(decision("a"))
        api.handler = { ok(CboSyncRecordResult("a", true, alreadyReceived = true)) }

        processor.syncPending()

        assertEquals(SyncStatus.SYNCED, dao.get("a").syncStatus)
    }

    // ── failures ───────────────────────────────────────────────────────────────────

    @Test
    fun partialBatch_eachDecisionGetsItsOwnOutcome() = runTest {
        dao.rows.value = listOf(decision("ok"), decision("again"), decision("bad"), decision("lost"))
        api.handler = {
            ok(
                CboSyncRecordResult("ok", true),
                CboSyncRecordResult("again", false, errorCode = "SERVER_ERROR", retryable = true),
                CboSyncRecordResult("bad", false, errorCode = CboSyncErrorCodes.VALIDATION_FAILED, retryable = false)
                // "lost" is not mentioned at all
            )
        }

        assertEquals(SyncRunResult.RETRY_LATER, processor.syncPending())

        assertEquals(SyncStatus.SYNCED, dao.get("ok").syncStatus)
        assertEquals(1, dao.get("again").retryCount)
        assertEquals("SERVER_ERROR", dao.get("again").syncErrorCode)
        assertEquals(SyncPolicy.MAX_RETRIES, dao.get("bad").retryCount)
        assertEquals(CboSyncErrorCodes.VALIDATION_FAILED, dao.get("bad").syncErrorCode)
        assertEquals(1, dao.get("lost").retryCount)
        assertNull(dao.get("lost").syncErrorCode)
    }

    @Test
    fun aRejectedDecision_isNeverSentAgain() = runTest {
        dao.rows.value = listOf(decision("bad"))
        api.handler = { ok(CboSyncRecordResult("bad", false, errorCode = CboSyncErrorCodes.VALIDATION_FAILED, retryable = false)) }

        assertEquals(SyncRunResult.DONE, processor.syncPending())
        processor.syncPending()
        processor.syncPending()

        assertEquals(1, api.requests.size)
        assertEquals(SyncStatus.FAILED, dao.get("bad").syncStatus)
    }

    @Test
    fun aRetryableFailure_isRetriedUntilItSucceeds_thenItsErrorIsCleared() = runTest {
        dao.rows.value = listOf(decision("a"))
        var calls = 0
        api.handler = { req ->
            calls++
            if (calls < 3) ok(CboSyncRecordResult("a", false, errorCode = "SERVER_ERROR", retryable = true)) else okAll(req)
        }

        processor.syncPending()
        processor.syncPending()
        assertEquals(2, dao.get("a").retryCount)
        processor.syncPending()

        assertEquals(SyncStatus.SYNCED, dao.get("a").syncStatus)
        assertNull(dao.get("a").syncErrorCode)
    }

    @Test
    fun aRetryableFailure_stopsAfterTheRetryBudget() = runTest {
        dao.rows.value = listOf(decision("a"))
        api.handler = { ok(CboSyncRecordResult("a", false, errorCode = "SERVER_ERROR", retryable = true)) }

        repeat(SyncPolicy.MAX_RETRIES + 3) { processor.syncPending() }

        assertEquals(SyncPolicy.MAX_RETRIES, api.requests.size)
        assertEquals(SyncPolicy.MAX_RETRIES, dao.get("a").retryCount)
    }

    @Test
    fun retryableFailureOnTheLastRetry_doesNotAskForAnotherRun() = runTest {
        dao.rows.value = listOf(decision("a", SyncStatus.FAILED, retryCount = SyncPolicy.MAX_RETRIES - 1))
        api.handler = { ok(CboSyncRecordResult("a", false, errorCode = "SERVER_ERROR", retryable = true)) }

        assertEquals(SyncRunResult.DONE, processor.syncPending())
    }

    @Test
    fun noConnection_leavesDecisionsPending_andAsksToRetry() = runTest {
        dao.rows.value = listOf(decision("a"))
        api.handler = { throw IOException("offline") }

        assertEquals(SyncRunResult.RETRY_LATER, processor.syncPending())

        assertEquals(SyncStatus.PENDING, dao.get("a").syncStatus)
        assertEquals(0, dao.get("a").retryCount)
    }

    @Test
    fun serverErrors_leaveDecisionsPending_andAskToRetry() = runTest {
        dao.rows.value = listOf(decision("a"))
        listOf(401, 403, 408, 429, 500, 503).forEach { code ->
            api.handler = { httpError(code) }
            assertEquals("HTTP $code", SyncRunResult.RETRY_LATER, processor.syncPending())
            assertEquals("HTTP $code", SyncStatus.PENDING, dao.get("a").syncStatus)
        }
    }

    @Test
    fun aWholeRequestRejection_marksTheBatchFailed() = runTest {
        dao.rows.value = listOf(decision("a"))
        api.handler = { httpError(400) }

        assertEquals(SyncRunResult.DONE, processor.syncPending())

        assertEquals(SyncStatus.FAILED, dao.get("a").syncStatus)
        assertEquals(1, dao.get("a").retryCount)
    }

    @Test
    fun anOfficerWhoCannotUseTheEndpoint_keepsTheirDecisionsPending_not403Failed() = runTest {
        // A 403 is "not allowed for this account", not a fault of the decision: it must not burn the retry budget.
        dao.rows.value = listOf(decision("a"))
        api.handler = { httpError(403) }

        processor.syncPending()

        assertEquals(0, dao.get("a").retryCount)
    }
}
