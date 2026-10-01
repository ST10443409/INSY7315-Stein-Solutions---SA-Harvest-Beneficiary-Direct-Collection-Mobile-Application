package com.example.client.sync

import com.example.client.data.local.dao.CboCollectionDao
import com.example.client.data.local.dao.ProductLineDao
import com.example.client.data.local.entity.CboCollectionEntity
import com.example.client.data.local.entity.ProductLineEntity
import com.example.client.data.local.entity.SyncStatus
import com.example.client.network.ApiEnvelope
import com.example.client.network.CboSyncRecordResult
import com.example.client.network.CboSyncRequest
import com.example.client.network.CboSyncResponse
import com.example.client.network.SyncApiService
import com.example.client.network.SyncRequest
import com.example.client.network.SyncResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import java.io.IOException

class CboSyncProcessorTest {

    private class FakeCollectionDao : CboCollectionDao() {
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
            rows.value = rows.value.map { if (it.id in ids) it.copy(syncStatus = SyncStatus.SYNCED) else it }
        }

        override suspend fun markFailed(ids: List<String>, now: Long) {
            rows.value = rows.value.map {
                if (it.id in ids) it.copy(syncStatus = SyncStatus.FAILED, retryCount = it.retryCount + 1) else it
            }
        }
    }

    private class FakeProductLineDao : ProductLineDao {
        override suspend fun insert(productLine: ProductLineEntity) = Unit
        override suspend fun update(productLine: ProductLineEntity) = Unit
        override fun getBySyncStatus(status: SyncStatus): Flow<List<ProductLineEntity>> = MutableStateFlow(emptyList())
        override suspend fun getForCollections(collectionIds: List<String>) = emptyList<ProductLineEntity>()
        override suspend fun setStatusForCollections(collectionIds: List<String>, status: SyncStatus, now: Long) = Unit
        override fun getByCollectionId(collectionId: String): Flow<List<ProductLineEntity>> = MutableStateFlow(emptyList())
    }

    private class FakeApi(var handler: (CboSyncRequest) -> Response<ApiEnvelope<CboSyncResponse>>) : SyncApiService {
        val requests = mutableListOf<CboSyncRequest>()
        override suspend fun syncData(request: SyncRequest): Response<SyncResponse> = error("unused")
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
        api.handler = { ok(CboSyncRecordResult("good", true), CboSyncRecordResult("bad", false, error = "invalid kg")) }

        processor.syncPending()

        assertEquals(SyncStatus.SYNCED, dao.get("good").syncStatus)
        assertEquals(SyncStatus.FAILED, dao.get("bad").syncStatus)
        assertEquals(1, dao.get("bad").retryCount)
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
