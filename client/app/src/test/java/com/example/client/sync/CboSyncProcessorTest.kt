package com.example.client.sync

import com.example.client.data.local.dao.CboCollectionDao
import com.example.client.data.local.dao.ProductLineDao
import com.example.client.data.local.entity.CboCollectionEntity
import com.example.client.data.local.entity.ProductLineEntity
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import java.io.IOException

class CboSyncProcessorTest {

    internal class FakeCollectionDao : CboCollectionDao() {
        val rows = MutableStateFlow<List<CboCollectionEntity>>(emptyList())

        fun get(id: String) = rows.value.first { it.id == id }

        override suspend fun insert(collection: CboCollectionEntity) {
            rows.value = rows.value.filterNot { it.id == collection.id } + collection
        }

        override suspend fun insertProductLines(productLines: List<ProductLineEntity>) = Unit
        override suspend fun update(collection: CboCollectionEntity) = insert(collection)
        override fun getBySyncStatus(status: SyncStatus): Flow<List<CboCollectionEntity>> =
            rows.map { l -> l.filter { it.syncStatus == status } }

        override fun getAll(): Flow<List<CboCollectionEntity>> = rows
        override fun observeAllNewestFirst(): Flow<List<CboCollectionEntity>> = rows
        override fun observeCountByStatus(status: SyncStatus): Flow<Int> =
            rows.map { l -> l.count { it.syncStatus == status } }

        override suspend fun getSyncable(maxRetries: Int) = rows.value.filter {
            it.syncStatus == SyncStatus.PENDING ||
                (it.syncStatus == SyncStatus.FAILED && it.retryCount < maxRetries)
        }

        override suspend fun markSynced(ids: List<String>, now: Long) {
            rows.value = rows.value.map {
                if (it.id in ids) it.copy(syncStatus = SyncStatus.SYNCED, syncErrorCode = null) else it
            }
        }

        override suspend fun markFailed(ids: List<String>, errorCode: String?, now: Long) {
            rows.value = rows.value.map {
                if (it.id in ids) {
                    it.copy(syncStatus = SyncStatus.FAILED, retryCount = it.retryCount + 1, syncErrorCode = errorCode)
                } else it
            }
        }

        override suspend fun markRejected(ids: List<String>, errorCode: String?, maxRetries: Int, now: Long) {
            rows.value = rows.value.map {
                if (it.id in ids) {
                    it.copy(syncStatus = SyncStatus.FAILED, retryCount = maxRetries, syncErrorCode = errorCode)
                } else it
            }
        }
    }

    internal class FakeProductLineDao : ProductLineDao {
        override suspend fun insert(productLine: ProductLineEntity) = Unit
        override suspend fun update(productLine: ProductLineEntity) = Unit
        override fun getBySyncStatus(status: SyncStatus): Flow<List<ProductLineEntity>> = MutableStateFlow(emptyList())
        override suspend fun getForCollections(collectionIds: List<String>) = emptyList<ProductLineEntity>()
        override suspend fun setStatusForCollections(collectionIds: List<String>, status: SyncStatus, now: Long) = Unit
        override fun getByCollectionId(collectionId: String): Flow<List<ProductLineEntity>> = MutableStateFlow(emptyList())
    }

    internal class FakeApi(var handler: (CboSyncRequest) -> Response<ApiEnvelope<CboSyncResponse>>) : SyncApiService {
        val requests = mutableListOf<CboSyncRequest>()
        override suspend fun syncData(request: SyncRequest): Response<SyncResponse> = error("unused")
        override suspend fun syncVettingDecisions(request: VettingSyncRequest): Response<ApiEnvelope<CboSyncResponse>> = error("unused")
        override suspend fun syncCboCollections(request: CboSyncRequest): Response<ApiEnvelope<CboSyncResponse>> {
            requests += request
            return handler(request)
        }
    }

    private lateinit var dao: FakeCollectionDao
    private lateinit var api: FakeApi
    private lateinit var processor: CboSyncProcessor

    private fun record(id: String, status: SyncStatus = SyncStatus.PENDING, retryCount: Int = 0) =
        CboCollectionEntity(
            id = id, cboId = "cbo", arrivalTime = "09:00", departureTime = null, donorName = "D",
            donorSigned = true, cboSigned = true, deliveryNote = "", noteAttached = false,
            collectNotes = "", shots = listOf(true), latitude = null, longitude = null,
            syncStatus = status, retryCount = retryCount
        )

    private fun ok(vararg results: CboSyncRecordResult) =
        Response.success(ApiEnvelope(success = true, data = CboSyncResponse(results.toList())))

    private fun httpError(code: Int): Response<ApiEnvelope<CboSyncResponse>> =
        Response.error(code, "{}".toResponseBody("application/json".toMediaType()))

    @Before
    fun setUp() {
        dao = FakeCollectionDao()
        api = FakeApi { ok() }
        processor = CboSyncProcessor(dao, FakeProductLineDao(), api)
    }

    @Test
    fun successfulRecords_becomeSynced() = runTest {
        dao.rows.value = listOf(record("a"), record("b"))
        api.handler = { req -> ok(*req.records.map { CboSyncRecordResult(it.id, true) }.toTypedArray()) }

        assertEquals(SyncRunResult.DONE, processor.syncPending())

        assertEquals(SyncStatus.SYNCED, dao.get("a").syncStatus)
        assertEquals(SyncStatus.SYNCED, dao.get("b").syncStatus)
    }

    @Test
    fun partialBatch_onlyFailedRecordsBecomeFailed() = runTest {
        dao.rows.value = listOf(record("good"), record("bad"))
        api.handler = {
            ok(
                CboSyncRecordResult("good", true),
                CboSyncRecordResult("bad", false, error = "try later", errorCode = "SERVER_ERROR", retryable = true)
            )
        }

        processor.syncPending()

        assertEquals(SyncStatus.SYNCED, dao.get("good").syncStatus)
        assertEquals(SyncStatus.FAILED, dao.get("bad").syncStatus)
        assertEquals(1, dao.get("bad").retryCount)
        assertEquals("SERVER_ERROR", dao.get("bad").syncErrorCode)
    }

    @Test
    fun nonRetryableFailure_isRejectedForGood_andKeepsItsErrorCode() = runTest {
        dao.rows.value = listOf(record("bad"))
        api.handler = {
            ok(CboSyncRecordResult("bad", false, error = "invalid kg", errorCode = CboSyncErrorCodes.VALIDATION_FAILED, retryable = false))
        }

        processor.syncPending()

        val bad = dao.get("bad")
        assertEquals(SyncStatus.FAILED, bad.syncStatus)
        assertEquals(CboSyncErrorCodes.VALIDATION_FAILED, bad.syncErrorCode)
        assertEquals(CboSyncProcessor.MAX_RETRIES, bad.retryCount) // no retries left
        assertTrue(dao.getSyncable(CboSyncProcessor.MAX_RETRIES).isEmpty())
    }

    @Test
    fun nonRetryableFailure_isNeverSentAgain() = runTest {
        dao.rows.value = listOf(record("bad"))
        api.handler = { ok(CboSyncRecordResult("bad", false, errorCode = CboSyncErrorCodes.VALIDATION_FAILED, retryable = false)) }

        processor.syncPending()
        processor.syncPending()
        processor.syncPending()

        assertEquals(1, api.requests.size)
    }

    @Test
    fun nonRetryableFailure_doesNotAskForAnotherRun() = runTest {
        dao.rows.value = listOf(record("bad"))
        api.handler = { ok(CboSyncRecordResult("bad", false, errorCode = CboSyncErrorCodes.VALIDATION_FAILED, retryable = false)) }

        assertEquals(SyncRunResult.DONE, processor.syncPending())
    }

    @Test
    fun duplicateDetected_isNotRetried_andIsTellingableApartFromAValidationFailure() = runTest {
        dao.rows.value = listOf(record("dup"), record("invalid"))
        api.handler = {
            ok(
                CboSyncRecordResult("dup", false, errorCode = CboSyncErrorCodes.DUPLICATE_DETECTED, retryable = false),
                CboSyncRecordResult("invalid", false, errorCode = CboSyncErrorCodes.VALIDATION_FAILED, retryable = false)
            )
        }

        processor.syncPending()

        assertEquals(CboSyncErrorCodes.DUPLICATE_DETECTED, dao.get("dup").syncErrorCode)
        assertEquals(CboSyncErrorCodes.VALIDATION_FAILED, dao.get("invalid").syncErrorCode)
        assertEquals(CboSyncProcessor.MAX_RETRIES, dao.get("dup").retryCount)
        assertEquals(CboSyncProcessor.MAX_RETRIES, dao.get("invalid").retryCount)
    }

    @Test
    fun mixedBatch_eachRecordIsHandledOnItsOwn() = runTest {
        dao.rows.value = listOf(record("ok"), record("again"), record("dup"), record("lost"))
        api.handler = {
            ok(
                CboSyncRecordResult("ok", true, alreadyReceived = true),
                CboSyncRecordResult("again", false, errorCode = "SERVER_ERROR", retryable = true),
                CboSyncRecordResult("dup", false, errorCode = CboSyncErrorCodes.DUPLICATE_DETECTED, retryable = false)
                // "lost" is not mentioned at all
            )
        }

        processor.syncPending()

        assertEquals(SyncStatus.SYNCED, dao.get("ok").syncStatus)
        assertEquals(1, dao.get("again").retryCount)
        assertEquals(CboSyncProcessor.MAX_RETRIES, dao.get("dup").retryCount)
        assertEquals(1, dao.get("lost").retryCount) // unknown outcome: one retry used, still retryable
        assertEquals(null, dao.get("lost").syncErrorCode)
    }

    @Test
    fun retryableFailure_withRetriesLeft_asksForARetryLater() = runTest {
        dao.rows.value = listOf(record("a"))
        api.handler = { ok(CboSyncRecordResult("a", false, errorCode = "SERVER_ERROR", retryable = true)) }

        assertEquals(SyncRunResult.RETRY_LATER, processor.syncPending())
    }

    @Test
    fun retryableFailure_onTheLastRetry_doesNotAskForAnother() = runTest {
        dao.rows.value = listOf(record("a", SyncStatus.FAILED, retryCount = CboSyncProcessor.MAX_RETRIES - 1))
        api.handler = { ok(CboSyncRecordResult("a", false, errorCode = "SERVER_ERROR", retryable = true)) }

        assertEquals(SyncRunResult.DONE, processor.syncPending())
        assertEquals(CboSyncProcessor.MAX_RETRIES, dao.get("a").retryCount)
    }

    @Test
    fun recordsKeepRetrying_untilTheServerAccepts_thenTheErrorCodeIsCleared() = runTest {
        dao.rows.value = listOf(record("a"))
        var calls = 0
        api.handler = { req ->
            calls++
            if (calls < 3) ok(CboSyncRecordResult("a", false, errorCode = "SERVER_ERROR", retryable = true))
            else ok(*req.records.map { CboSyncRecordResult(it.id, true) }.toTypedArray())
        }

        processor.syncPending()
        processor.syncPending()
        assertEquals(2, dao.get("a").retryCount)
        processor.syncPending()

        assertEquals(SyncStatus.SYNCED, dao.get("a").syncStatus)
        assertEquals(null, dao.get("a").syncErrorCode)
    }

    @Test
    fun retryableFailure_stopsAfterMaxRetries() = runTest {
        dao.rows.value = listOf(record("a"))
        api.handler = { ok(CboSyncRecordResult("a", false, errorCode = "SERVER_ERROR", retryable = true)) }

        repeat(CboSyncProcessor.MAX_RETRIES + 3) { processor.syncPending() }

        assertEquals(CboSyncProcessor.MAX_RETRIES, api.requests.size)
        assertEquals(CboSyncProcessor.MAX_RETRIES, dao.get("a").retryCount)
    }

    @Test
    fun recordMissingFromResponse_countsAsFailed() = runTest {
        dao.rows.value = listOf(record("a"), record("b"))
        api.handler = { ok(CboSyncRecordResult("a", true)) }

        processor.syncPending()

        assertEquals(SyncStatus.SYNCED, dao.get("a").syncStatus)
        assertEquals(SyncStatus.FAILED, dao.get("b").syncStatus)
    }

    @Test
    fun networkUnreachable_leavesRecordsPending_andAsksToRetry() = runTest {
        dao.rows.value = listOf(record("a"))
        api.handler = { throw IOException("offline") }

        assertEquals(SyncRunResult.RETRY_LATER, processor.syncPending())

        assertEquals(SyncStatus.PENDING, dao.get("a").syncStatus)
        assertEquals(0, dao.get("a").retryCount)
    }

    @Test
    fun serverError_leavesRecordsPending_andAsksToRetry() = runTest {
        dao.rows.value = listOf(record("a"))
        api.handler = { httpError(503) }

        assertEquals(SyncRunResult.RETRY_LATER, processor.syncPending())
        assertEquals(SyncStatus.PENDING, dao.get("a").syncStatus)
    }

    @Test
    fun wholeRequestRejected_marksBatchFailed() = runTest {
        dao.rows.value = listOf(record("a"))
        api.handler = { httpError(400) }

        assertEquals(SyncRunResult.DONE, processor.syncPending())
        assertEquals(SyncStatus.FAILED, dao.get("a").syncStatus)
        assertEquals(1, dao.get("a").retryCount)
    }

    @Test
    fun failedRecords_areRetried_untilTheyRunOutOfRetries() = runTest {
        dao.rows.value = listOf(
            record("again", SyncStatus.FAILED, retryCount = 1),
            record("exhausted", SyncStatus.FAILED, retryCount = CboSyncProcessor.MAX_RETRIES),
            record("done", SyncStatus.SYNCED)
        )
        api.handler = { req -> ok(*req.records.map { CboSyncRecordResult(it.id, true) }.toTypedArray()) }

        processor.syncPending()

        assertEquals(listOf("again"), api.requests.single().records.map { it.id })
        assertEquals(SyncStatus.SYNCED, dao.get("again").syncStatus)
        assertEquals(SyncStatus.FAILED, dao.get("exhausted").syncStatus)
    }

    @Test
    fun nothingToSync_makesNoRequest() = runTest {
        dao.rows.value = listOf(record("done", SyncStatus.SYNCED))

        assertEquals(SyncRunResult.DONE, processor.syncPending())
        assertEquals(0, api.requests.size)
    }
}
