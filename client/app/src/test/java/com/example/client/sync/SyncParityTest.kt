package com.example.client.sync

import com.example.client.data.local.entity.CboCollectionEntity
import com.example.client.data.local.entity.SyncStatus
import com.example.client.network.ApiEnvelope
import com.example.client.network.CboSyncRecordResult
import com.example.client.network.CboSyncResponse
import com.example.client.testing.FakeVettingDecisionDao
import com.example.client.testing.sampleDecision
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.Response
import java.io.IOException

/**
 * Collections and decisions must be treated identically by a sync. Both processors are built on [BatchSyncRunner]; this
 * test runs the same server answers through each and requires the same outcome for the record, so a future change that
 * makes one of them special-case something shows up here.
 */
class SyncParityTest {

    private data class Outcome(val run: SyncRunResult, val status: SyncStatus, val retryCount: Int, val errorCode: String?)

    /** One server answer for a single record (or a transport/HTTP failure), applied to a record that starts with [startRetries] used. */
    private class Scenario(val name: String, val startRetries: Int = 0, val answer: (id: String) -> () -> Response<ApiEnvelope<CboSyncResponse>>)

    private fun envelope(result: CboSyncRecordResult?) =
        Response.success(ApiEnvelope(success = true, data = CboSyncResponse(listOfNotNull(result))))

    private fun httpError(code: Int): Response<ApiEnvelope<CboSyncResponse>> =
        Response.error(code, "{}".toResponseBody("application/json".toMediaType()))

    private val scenarios = listOf(
        Scenario("success") { id -> { envelope(CboSyncRecordResult(id, true)) } },
        Scenario("already received") { id -> { envelope(CboSyncRecordResult(id, true, alreadyReceived = true)) } },
        Scenario("validation failed") { id -> { envelope(CboSyncRecordResult(id, false, errorCode = "VALIDATION_FAILED", retryable = false)) } },
        Scenario("duplicate detected") { id -> { envelope(CboSyncRecordResult(id, false, errorCode = "DUPLICATE_DETECTED", retryable = false)) } },
        Scenario("retryable server error") { id -> { envelope(CboSyncRecordResult(id, false, errorCode = "SERVER_ERROR", retryable = true)) } },
        Scenario("retryable on the last retry", startRetries = SyncPolicy.MAX_RETRIES - 1) { id ->
            { envelope(CboSyncRecordResult(id, false, errorCode = "SERVER_ERROR", retryable = true)) }
        },
        Scenario("not mentioned in the answer") { _ -> { envelope(null) } },
        Scenario("whole request rejected (400)") { _ -> { httpError(400) } },
        Scenario("not signed in (401)") { _ -> { httpError(401) } },
        Scenario("not allowed (403)") { _ -> { httpError(403) } },
        Scenario("throttled (429)") { _ -> { httpError(429) } },
        Scenario("server down (503)") { _ -> { httpError(503) } },
        Scenario("no connection") { _ -> { throw IOException("offline") } },
        Scenario("200 with no body") { _ -> { Response.success(ApiEnvelope<CboSyncResponse>(success = true, data = null)) } },
    )

    private suspend fun viaCollections(scenario: Scenario): Outcome {
        val dao = CboSyncProcessorTest.FakeCollectionDao()
        val record = CboCollectionEntity(
            id = "r", cboId = "c", arrivalTime = "09:00", departureTime = null, donorName = "D", donorSigned = true, cboSigned = true,
            deliveryNote = "", noteAttached = false, collectNotes = "", shots = listOf(true), latitude = null, longitude = null,
            retryCount = scenario.startRetries
        )
        dao.rows.value = listOf(record)
        val answer = scenario.answer("r")
        val api = CboSyncProcessorTest.FakeApi { answer() }
        val run = CboSyncProcessor(dao, CboSyncProcessorTest.FakeProductLineDao(), api).syncPending()
        return dao.get("r").let { Outcome(run, it.syncStatus, it.retryCount, it.syncErrorCode) }
    }

    private suspend fun viaDecisions(scenario: Scenario): Outcome {
        val dao = FakeVettingDecisionDao()
        dao.rows.value = listOf(sampleDecision().copy(id = "r", retryCount = scenario.startRetries))
        val answer = scenario.answer("r")
        val api = VettingSyncProcessorTest.FakeApi { answer() }
        val run = VettingSyncProcessor(dao, api).syncPending()
        return dao.get("r").let { Outcome(run, it.syncStatus, it.retryCount, it.syncErrorCode) }
    }

    @Test
    fun aCollectionAndADecision_getTheSameTreatmentForEveryServerAnswer() = runTest {
        scenarios.forEach { scenario ->
            assertEquals(scenario.name, viaCollections(scenario), viaDecisions(scenario))
        }
    }

    @Test
    fun theScenarios_actuallyCoverEveryKindOfOutcome() = runTest {
        // Guard against the comparison above passing vacuously: the scenarios must produce several different outcomes.
        val outcomes = scenarios.map { viaDecisions(it) }.toSet()
        assertEquals(setOf(SyncStatus.SYNCED, SyncStatus.FAILED, SyncStatus.PENDING), outcomes.map { it.status }.toSet())
        assertEquals(setOf(SyncRunResult.DONE, SyncRunResult.RETRY_LATER), outcomes.map { it.run }.toSet())
        assert(outcomes.size >= 6)
    }
}
