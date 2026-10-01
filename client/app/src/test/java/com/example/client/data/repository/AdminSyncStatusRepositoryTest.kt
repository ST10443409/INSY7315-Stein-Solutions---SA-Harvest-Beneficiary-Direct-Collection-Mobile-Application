package com.example.client.data.repository

import com.example.client.data.parseIsoInstantMillis
import com.example.client.network.AdminApiService
import com.example.client.network.AdminSyncStatusDto
import com.example.client.network.ApiEnvelope
import com.example.client.network.DismissRequestDto
import com.example.client.network.FormSyncCountsDto
import com.google.gson.JsonParseException
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import java.io.IOException

class AdminSyncStatusRepositoryTest {

    private class FakeApi(var handler: suspend () -> Response<ApiEnvelope<AdminSyncStatusDto>>) : AdminApiService {
        override suspend fun getSyncStatus() = handler()

        // The failed-sync endpoints are covered by AdminSyncResolutionRepositoryTest.
        override suspend fun getAttention(page: Int, pageSize: Int) = throw NotImplementedError()
        override suspend fun getSyncRecord(id: String, form: String) = throw NotImplementedError()
        override suspend fun retrySync(id: String, form: String) = throw NotImplementedError()
        override suspend fun dismissSync(id: String, form: String, request: DismissRequestDto) = throw NotImplementedError()
    }

    private fun repository(handler: suspend () -> Response<ApiEnvelope<AdminSyncStatusDto>>) = AdminSyncStatusRepositoryImpl(FakeApi(handler))

    private fun error(code: Int): Response<ApiEnvelope<AdminSyncStatusDto>> =
        Response.error(code, """{"success":false}""".toResponseBody("application/json".toMediaType()))

    private val dto = AdminSyncStatusDto(
        cboCollections = FormSyncCountsDto(total = 21, waiting = 2, retrying = 1, needsAttention = 3, forwarded = 10, duplicates = 4, dismissed = 1),
        vettingDecisions = FormSyncCountsDto(total = 10, waiting = 1, forwarded = 7, superseded = 2),
        generatedAt = "2026-10-01T10:00:00+00:00"
    )

    @Test
    fun theServersCounts_areHandedOverUnchanged_withTheTimeTheServerTookThem() = runTest {
        val result = repository { Response.success(ApiEnvelope(success = true, data = dto)) }.load(now = { 5L })

        val snapshot = (result as SyncStatusResult.Loaded).snapshot
        assertEquals(FormSyncCounts(21, 2, 1, 3, 10, 4, 0, 1), snapshot.cboCollections)
        assertEquals(FormSyncCounts(10, 1, 0, 0, 7, 0, 2, 0), snapshot.vettingDecisions)
        assertEquals(parseIsoInstantMillis("2026-10-01T10:00:00+00:00"), snapshot.takenAtMillis)
    }

    @Test
    fun withoutAServerTime_theDeviceTimeIsUsed() = runTest {
        val result = repository { Response.success(ApiEnvelope(success = true, data = dto.copy(generatedAt = null))) }.load(now = { 5L })

        assertEquals(5L, (result as SyncStatusResult.Loaded).snapshot.takenAtMillis)
    }

    @Test
    fun noConnection_isOffline() = runTest {
        assertEquals(SyncStatusResult.Offline, repository { throw IOException("no route") }.load())
    }

    @Test
    fun aRefusedAccount_isDenied() = runTest {
        assertEquals(SyncStatusResult.Denied, repository { error(403) }.load())
        assertEquals(SyncStatusResult.Denied, repository { error(401) }.load())
    }

    @Test
    fun aServerError_isFailed() = runTest {
        assertEquals(SyncStatusResult.Failed, repository { error(500) }.load())
        assertEquals(SyncStatusResult.Failed, repository { error(503) }.load())
    }

    @Test
    fun anUnreadableOrEmptyAnswer_isFailed() = runTest {
        assertEquals(SyncStatusResult.Failed, repository { throw JsonParseException("bad json") }.load())
        assertEquals(SyncStatusResult.Failed, repository { Response.success(ApiEnvelope<AdminSyncStatusDto>(success = true, data = null)) }.load())
    }

    @Test
    fun theStatesOfEachForm_addUpToItsTotal() = runTest {
        val result = repository { Response.success(ApiEnvelope(success = true, data = dto)) }.load() as SyncStatusResult.Loaded

        listOf(result.snapshot.cboCollections, result.snapshot.vettingDecisions).forEach {
            assertTrue(it.total == it.waiting + it.retrying + it.needsAttention + it.forwarded + it.duplicates + it.superseded + it.dismissed)
        }
    }
}
