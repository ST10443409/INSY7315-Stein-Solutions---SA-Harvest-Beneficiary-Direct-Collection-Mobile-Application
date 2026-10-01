package com.example.client.data.repository

import com.example.client.data.local.dao.FoodspaceBeneficiaryDao
import com.example.client.data.parseIsoInstantMillis
import com.example.client.data.local.entity.FoodspaceBeneficiaryRecord
import com.example.client.network.ApiEnvelope
import com.example.client.network.VettingApiService
import com.example.client.network.VettingRecordsPageDto
import com.example.client.testing.InMemoryRecordsMetaStore
import com.example.client.testing.sampleRecord
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import java.io.IOException

class VettingRecordsRepositoryTest {

    private class FakeDao : FoodspaceBeneficiaryDao() {
        val rows = MutableStateFlow<List<FoodspaceBeneficiaryRecord>>(emptyList())
        var replaceCalls = 0

        override suspend fun insert(record: FoodspaceBeneficiaryRecord) {
            rows.value = rows.value.filterNot { it.id == record.id } + record
        }

        override suspend fun insertAll(records: List<FoodspaceBeneficiaryRecord>) {
            records.forEach { insert(it) }
        }

        override suspend fun update(record: FoodspaceBeneficiaryRecord) = insert(record)
        override fun getAll(): Flow<List<FoodspaceBeneficiaryRecord>> = rows
        override fun observeAllByName(): Flow<List<FoodspaceBeneficiaryRecord>> =
            rows.map { l -> l.sortedWith(compareBy({ it.legalName.lowercase() }, { it.id })) }

        override fun observeById(id: String): Flow<FoodspaceBeneficiaryRecord?> = rows.map { l -> l.firstOrNull { it.id == id } }
        override suspend fun getById(id: String) = rows.value.firstOrNull { it.id == id }
        override suspend fun deleteAll() {
            rows.value = emptyList()
        }

        override suspend fun replaceAll(records: List<FoodspaceBeneficiaryRecord>) {
            replaceCalls++
            super.replaceAll(records)
        }
    }

    private class FakeApi(var handler: suspend (page: Int, pageSize: Int) -> Response<ApiEnvelope<VettingRecordsPageDto>>) : VettingApiService {
        val requested = mutableListOf<Pair<Int, Int>>()
        override suspend fun getRecords(page: Int, pageSize: Int): Response<ApiEnvelope<VettingRecordsPageDto>> {
            requested += page to pageSize
            return handler(page, pageSize)
        }
    }

    private lateinit var dao: FakeDao
    private lateinit var store: InMemoryRecordsMetaStore
    private lateinit var api: FakeApi
    private lateinit var repository: VettingRecordsRepositoryImpl

    private fun page(
        items: List<FoodspaceBeneficiaryRecord>, page: Int = 1, hasMore: Boolean = false,
        stale: Boolean = false, fetchedAt: String? = "2026-10-01T10:00:00+00:00"
    ) = Response.success(
        ApiEnvelope(
            success = true,
            data = VettingRecordsPageDto(items, page, 100, items.size, hasMore, fetchedAt, stale)
        )
    )

    private fun httpError(code: Int): Response<ApiEnvelope<VettingRecordsPageDto>> =
        Response.error(code, """{"success":false}""".toResponseBody("application/json".toMediaType()))

    @Before
    fun setUp() {
        dao = FakeDao()
        store = InMemoryRecordsMetaStore()
        api = FakeApi { _, _ -> page(emptyList()) }
        repository = VettingRecordsRepositoryImpl(dao, api, store)
    }

    @Test
    fun aSuccessfulFetch_replacesTheCache_andRemembersWhenItWasTaken() = runTest {
        dao.rows.value = listOf(sampleRecord("old"))
        api.handler = { _, _ -> page(listOf(sampleRecord("a"), sampleRecord("b"))) }

        assertEquals(RefreshOutcome.UPDATED, repository.refresh())

        assertEquals(listOf("a", "b"), dao.rows.value.map { it.id })
        val meta = repository.meta.value
        assertNotNull(meta)
        assertEquals(false, meta!!.stale)
        assertEquals(meta, store.stored) // persisted for the next launch
    }

    @Test
    fun theBackendsFetchTime_isUsed_notTheDevicesClock() = runTest {
        api.handler = { _, _ -> page(listOf(sampleRecord("a")), fetchedAt = "2026-10-01T10:00:00+00:00") }

        repository.refresh(now = { 42L })

        assertEquals(parseIsoInstantMillis("2026-10-01T10:00:00Z")!!, repository.meta.value!!.fetchedAtMillis)
    }

    @Test
    fun withoutAFetchTimeFromTheBackend_theDevicesClockIsUsed() = runTest {
        api.handler = { _, _ -> page(listOf(sampleRecord("a")), fetchedAt = null) }

        repository.refresh(now = { 42L })

        assertEquals(42L, repository.meta.value!!.fetchedAtMillis)
    }

    @Test
    fun aStaleServerCopy_isRememberedAsStale() = runTest {
        api.handler = { _, _ -> page(listOf(sampleRecord("a")), stale = true) }

        repository.refresh()

        assertTrue(repository.meta.value!!.stale)
    }

    @Test
    fun everyPageIsFetched_atTheLargestSizeTheBackendAllows_andJoinedIntoOneList() = runTest {
        api.handler = { p, _ ->
            when (p) {
                1 -> page(listOf(sampleRecord("a"), sampleRecord("b")), page = 1, hasMore = true)
                2 -> page(listOf(sampleRecord("c")), page = 2, hasMore = true)
                else -> page(listOf(sampleRecord("d")), page = 3, hasMore = false)
            }
        }

        assertEquals(RefreshOutcome.UPDATED, repository.refresh())

        assertEquals(listOf(1 to 100, 2 to 100, 3 to 100), api.requested)
        assertEquals(listOf("a", "b", "c", "d"), dao.rows.value.map { it.id })
        assertEquals(1, dao.replaceCalls) // one swap at the end, not one per page
    }

    @Test
    fun aRecordSeenOnTwoPages_isKeptOnce() = runTest {
        api.handler = { p, _ ->
            if (p == 1) page(listOf(sampleRecord("a"), sampleRecord("b")), hasMore = true)
            else page(listOf(sampleRecord("b", legalName = "Newer"), sampleRecord("c")), page = 2)
        }

        repository.refresh()

        assertEquals(listOf("a", "b", "c"), dao.rows.value.map { it.id }.sorted())
        assertEquals("Newer", dao.rows.value.single { it.id == "b" }.legalName)
    }

    @Test
    fun anEmptyList_isAValidAnswer_andEmptiesTheCache() = runTest {
        dao.rows.value = listOf(sampleRecord("old"))

        assertEquals(RefreshOutcome.UPDATED, repository.refresh())

        assertTrue(dao.rows.value.isEmpty())
    }

    // ── failures never touch what is already on the device ─────────────────────────

    @Test
    fun noConnection_isOffline_andTheCacheIsKept() = runTest {
        dao.rows.value = listOf(sampleRecord("keep"))
        api.handler = { _, _ -> throw IOException("no route to host") }

        assertEquals(RefreshOutcome.OFFLINE, repository.refresh())

        assertEquals(listOf("keep"), dao.rows.value.map { it.id })
        assertEquals(0, dao.replaceCalls)
        assertNull(repository.meta.value)
    }

    @Test
    fun aFailureOnALaterPage_keepsTheWholeOldCache_notAHalfNewOne() = runTest {
        dao.rows.value = listOf(sampleRecord("keep-1"), sampleRecord("keep-2"))
        api.handler = { p, _ ->
            if (p == 1) page(listOf(sampleRecord("new-1")), hasMore = true) else throw IOException("connection dropped")
        }

        assertEquals(RefreshOutcome.OFFLINE, repository.refresh())

        assertEquals(listOf("keep-1", "keep-2"), dao.rows.value.map { it.id })
        assertEquals(0, dao.replaceCalls)
    }

    @Test
    fun a503_meansTheServerHasNothingToGive_andTheCacheIsKept() = runTest {
        dao.rows.value = listOf(sampleRecord("keep"))
        api.handler = { _, _ -> httpError(503) }

        assertEquals(RefreshOutcome.SERVER_UNAVAILABLE, repository.refresh())

        assertEquals(listOf("keep"), dao.rows.value.map { it.id })
    }

    @Test
    fun otherErrors_areAFailure_andTheCacheIsKept() = runTest {
        dao.rows.value = listOf(sampleRecord("keep"))
        listOf(400, 401, 403, 404, 500).forEach { code ->
            api.handler = { _, _ -> httpError(code) }
            assertEquals("HTTP $code", RefreshOutcome.FAILED, repository.refresh())
        }
        assertEquals(listOf("keep"), dao.rows.value.map { it.id })
    }

    @Test
    fun aSuccessWithoutAnEnvelopeBody_isAFailure() = runTest {
        api.handler = { _, _ -> Response.success(ApiEnvelope<VettingRecordsPageDto>(success = true, data = null)) }

        assertEquals(RefreshOutcome.FAILED, repository.refresh())
    }

    @Test
    fun aListThatNeverEnds_isGivenUpOn() = runTest {
        dao.rows.value = listOf(sampleRecord("keep"))
        api.handler = { p, _ -> page(listOf(sampleRecord("r$p")), page = p, hasMore = true) }

        assertEquals(RefreshOutcome.FAILED, repository.refresh())

        assertEquals(VettingRecordsRepositoryImpl.MAX_PAGES, api.requested.size)
        assertEquals(listOf("keep"), dao.rows.value.map { it.id })
    }

    // ── reading ────────────────────────────────────────────────────────────────────

    @Test
    fun theCacheIsReadAToZ_withoutAnyNetwork() = runTest {
        dao.rows.value = listOf(sampleRecord("1", legalName = "umlazi"), sampleRecord("2", legalName = "Alpha"), sampleRecord("3", legalName = "Beta"))

        assertEquals(listOf("Alpha", "Beta", "umlazi"), repository.observeRecords().first().map { it.legalName })
        assertEquals("Beta", repository.observeRecord("3").first()?.legalName)
        assertNull(repository.observeRecord("missing").first())
        assertEquals(0, api.requested.size)
    }

    @Test
    fun theLastFetchIsRememberedAcrossRestarts() {
        store.stored = RecordsMeta(fetchedAtMillis = 123L, stale = true)

        val restarted = VettingRecordsRepositoryImpl(dao, api, store)

        assertEquals(RecordsMeta(123L, true), restarted.meta.value)
    }

    @Test
    fun twoRefreshesAtOnce_runOneAfterTheOther_notTogether() = runTest {
        val gate = CompletableDeferred<Unit>()
        var running = 0
        var maxRunning = 0
        api.handler = { _, _ ->
            running++
            maxRunning = maxOf(maxRunning, running)
            gate.await()
            running--
            page(listOf(sampleRecord("a")))
        }

        val first = async { repository.refresh() }
        val second = async { repository.refresh() }
        gate.complete(Unit)

        assertEquals(RefreshOutcome.UPDATED, first.await())
        assertEquals(RefreshOutcome.UPDATED, second.await())
        assertEquals(1, maxRunning)
    }
}
