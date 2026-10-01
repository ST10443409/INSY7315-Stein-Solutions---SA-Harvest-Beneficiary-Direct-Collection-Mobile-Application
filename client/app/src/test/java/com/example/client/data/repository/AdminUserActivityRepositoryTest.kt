package com.example.client.data.repository

import com.example.client.auth.UserRole
import com.example.client.data.parseIsoInstantMillis
import com.example.client.network.AdminApiService
import com.example.client.network.AdminSyncStatusDto
import com.example.client.network.ApiEnvelope
import com.example.client.network.DismissRequestDto
import com.example.client.network.ResolutionDto
import com.example.client.network.SyncAttentionPageDto
import com.example.client.network.SyncRecordDetailDto
import com.example.client.network.UserActivityItemDto
import com.example.client.network.UserActivityPageDto
import com.google.gson.JsonParseException
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import java.io.IOException

class AdminUserActivityRepositoryTest {

    private data class Request(val user: String?, val role: String?, val from: String?, val to: String?, val page: Int, val pageSize: Int)

    private class FakeApi : AdminApiService {
        var answer: suspend () -> Response<ApiEnvelope<UserActivityPageDto>> = { ok(UserActivityPageDto()) }
        val requests = mutableListOf<Request>()

        override suspend fun getUserActivity(user: String?, role: String?, from: String?, to: String?, page: Int, pageSize: Int):
            Response<ApiEnvelope<UserActivityPageDto>> {
            requests += Request(user, role, from, to, page, pageSize)
            return answer()
        }

        override suspend fun getSyncStatus(): Response<ApiEnvelope<AdminSyncStatusDto>> = throw NotImplementedError()
        override suspend fun getAttention(page: Int, pageSize: Int): Response<ApiEnvelope<SyncAttentionPageDto>> = throw NotImplementedError()
        override suspend fun getSyncRecord(id: String, form: String): Response<ApiEnvelope<SyncRecordDetailDto>> = throw NotImplementedError()
        override suspend fun retrySync(id: String, form: String): Response<ApiEnvelope<ResolutionDto>> = throw NotImplementedError()
        override suspend fun dismissSync(id: String, form: String, request: DismissRequestDto): Response<ApiEnvelope<ResolutionDto>> =
            throw NotImplementedError()
    }

    private val api = FakeApi()
    private val repository = AdminUserActivityRepositoryImpl(api)

    companion object {
        fun <T> ok(data: T): Response<ApiEnvelope<T>> = Response.success(ApiEnvelope(success = true, data = data))

        fun error(code: Int): Response<ApiEnvelope<UserActivityPageDto>> =
            Response.error(code, """{"success":false}""".toResponseBody("application/json".toMediaType()))

        fun itemDto(id: String, form: String = "CBO_COLLECTION", user: String? = "cbo_test_user", role: String? = "CBO_COLLECTION") = UserActivityItemDto(
            id = id, form = form, user = user, role = role, at = "2026-10-01T10:00:00+00:00", receivedAt = "2026-10-01T10:05:00+00:00", label = "Label $id"
        )
    }

    // ── what is asked ──────────────────────────────────────────────────────────────

    @Test
    fun theFilter_isSentAsQueryParameters_withTheRoleByItsServerName() = runTest {
        repository.page(ActivityFilter(user = "vetting_test_user", role = UserRole.VETTING, from = "2026-09-01", to = "2026-09-20"), page = 3)

        assertEquals(Request("vetting_test_user", "VETTING", "2026-09-01", "2026-09-20", 3, AdminUserActivityRepositoryImpl.PAGE_SIZE), api.requests.single())
    }

    @Test
    fun anEmptyFilter_sendsNoFilters_soTheServerAppliesItsDefaultWindow() = runTest {
        repository.page(ActivityFilter(), page = 1)

        assertEquals(Request(null, null, null, null, 1, AdminUserActivityRepositoryImpl.PAGE_SIZE), api.requests.single())
    }

    @Test
    fun blankOrPaddedText_isTrimmed_andBlankIsLeftOut() = runTest {
        repository.page(ActivityFilter(user = "  cbo_test_user  ", from = "   ", to = " 2026-09-20 "), page = 1)

        val request = api.requests.single()
        assertEquals("cbo_test_user", request.user)
        assertNull(request.from)
        assertEquals("2026-09-20", request.to)
    }

    // ── what comes back ────────────────────────────────────────────────────────────

    @Test
    fun aPage_isReadWithWhoWhatAndWhen_andTheWindowTheServerApplied() = runTest {
        api.answer = {
            ok(
                UserActivityPageDto(
                    items = listOf(itemDto("c1"), itemDto("d1", form = "VETTING_DECISION", user = "vetting_test_user", role = "VETTING")),
                    page = 2, pageSize = 50, totalCount = 71, hasMore = true, from = "2026-09-26", to = "2026-10-02"
                )
            )
        }

        val page = (repository.page(ActivityFilter(), 2) as AdminResult.Success).value

        assertEquals(listOf("c1", "d1"), page.items.map { it.id })
        assertEquals(listOf(SyncForm.CBO_COLLECTION, SyncForm.VETTING_DECISION), page.items.map { it.form })
        assertEquals(listOf("cbo_test_user", "vetting_test_user"), page.items.map { it.user })
        assertEquals(listOf(UserRole.CBO_COLLECTION, UserRole.VETTING), page.items.map { it.role })
        assertEquals(parseIsoInstantMillis("2026-10-01T10:00:00+00:00"), page.items[0].atMillis)
        assertEquals("Label c1", page.items[0].label)
        assertEquals(71, page.totalCount)
        assertTrue(page.hasMore)
        assertEquals("2026-09-26", page.from)
        assertEquals("2026-10-02", page.to)
    }

    @Test
    fun aRecordWithNoUserOrRole_isStillRead() = runTest {
        api.answer = { ok(UserActivityPageDto(items = listOf(itemDto("old", user = null, role = null)))) }

        val item = (repository.page(ActivityFilter(), 1) as AdminResult.Success).value.items.single()

        assertNull(item.user)
        assertNull(item.role)
    }

    @Test
    fun aRoleOrFormTheAppDoesNotKnow_doesNotBreakReading() = runTest {
        api.answer = { ok(UserActivityPageDto(items = listOf(itemDto("x", form = "SOMETHING_NEW", role = "SUPERUSER")))) }

        val item = (repository.page(ActivityFilter(), 1) as AdminResult.Success).value.items.single()

        assertNull(item.role)
    }

    @Test
    fun noActivity_isAnEmptySuccess() = runTest {
        val page = (repository.page(ActivityFilter(), 1) as AdminResult.Success).value

        assertTrue(page.items.isEmpty())
        assertEquals(0, page.totalCount)
    }

    // ── what the server can answer ─────────────────────────────────────────────────

    @Test
    fun noConnection_isOffline() = runTest {
        api.answer = { throw IOException("no route") }

        assertEquals(AdminResult.Offline, repository.page(ActivityFilter(), 1))
    }

    @Test
    fun aRefusedAccount_isDenied() = runTest {
        api.answer = { error(403) }
        assertEquals(AdminResult.Denied, repository.page(ActivityFilter(), 1))
        api.answer = { error(401) }
        assertEquals(AdminResult.Denied, repository.page(ActivityFilter(), 1))
    }

    @Test
    fun aRefusedFilter_is_Invalid() = runTest {
        api.answer = { error(400) }

        assertEquals(AdminResult.Invalid(null), repository.page(ActivityFilter(from = "nonsense"), 1))
    }

    @Test
    fun aServerErrorOrUnreadableAnswer_isFailed() = runTest {
        api.answer = { error(500) }
        assertEquals(AdminResult.Failed, repository.page(ActivityFilter(), 1))
        api.answer = { throw JsonParseException("bad json") }
        assertEquals(AdminResult.Failed, repository.page(ActivityFilter(), 1))
    }
}
