package com.example.client.data.repository

import com.example.client.data.parseIsoInstantMillis
import com.example.client.network.AdminActionEntryDto
import com.example.client.network.AdminApiService
import com.example.client.network.AdminSyncStatusDto
import com.example.client.network.ApiEnvelope
import com.example.client.network.DismissRequestDto
import com.example.client.network.DuplicateOfDto
import com.example.client.network.ResolutionDto
import com.example.client.network.SyncAttentionItemDto
import com.example.client.network.SyncAttentionPageDto
import com.example.client.network.SyncRecordDetailDto
import com.example.client.network.UserActivityPageDto
import com.google.gson.JsonParseException
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import java.io.IOException

class AdminSyncResolutionRepositoryTest {

    /** Answers each endpoint from a lambda a test sets, and remembers what it was asked. */
    private class FakeApi : AdminApiService {
        var attention: suspend (page: Int, pageSize: Int) -> Response<ApiEnvelope<SyncAttentionPageDto>> = { _, _ -> ok(SyncAttentionPageDto()) }
        var record: suspend () -> Response<ApiEnvelope<SyncRecordDetailDto>> = { ok(detailDto()) }
        var retry: suspend () -> Response<ApiEnvelope<ResolutionDto>> = { ok(ResolutionDto("NEEDS_ATTENTION", detailDto(state = "FORWARDED"))) }
        var dismiss: suspend () -> Response<ApiEnvelope<ResolutionDto>> = { ok(ResolutionDto("NEEDS_ATTENTION", detailDto(state = "DISMISSED"))) }

        val attentionRequests = mutableListOf<Pair<Int, Int>>()
        val recordRequests = mutableListOf<Pair<String, String>>()
        val retryRequests = mutableListOf<Pair<String, String>>()
        val dismissRequests = mutableListOf<Triple<String, String, String>>()

        override suspend fun getSyncStatus(): Response<ApiEnvelope<AdminSyncStatusDto>> = throw NotImplementedError()

        override suspend fun getAttention(page: Int, pageSize: Int): Response<ApiEnvelope<SyncAttentionPageDto>> {
            attentionRequests += page to pageSize
            return attention(page, pageSize)
        }

        override suspend fun getSyncRecord(id: String, form: String): Response<ApiEnvelope<SyncRecordDetailDto>> {
            recordRequests += id to form
            return record()
        }

        override suspend fun retrySync(id: String, form: String): Response<ApiEnvelope<ResolutionDto>> {
            retryRequests += id to form
            return retry()
        }

        override suspend fun dismissSync(id: String, form: String, request: DismissRequestDto): Response<ApiEnvelope<ResolutionDto>> {
            dismissRequests += Triple(id, form, request.reason)
            return dismiss()
        }

        override suspend fun getUserActivity(user: String?, role: String?, from: String?, to: String?, page: Int, pageSize: Int):
            Response<ApiEnvelope<UserActivityPageDto>> = throw NotImplementedError()
    }

    private val api = FakeApi()
    private val repository = AdminSyncResolutionRepositoryImpl(api)

    companion object {
        fun <T> ok(data: T): Response<ApiEnvelope<T>> = Response.success(ApiEnvelope(success = true, data = data))

        fun <T> error(code: Int, message: String? = null): Response<ApiEnvelope<T>> {
            val body = if (message == null) """{"success":false}"""
            else """{"success":false,"data":null,"error":{"code":"X","message":"$message"}}"""
            return Response.error(code, body.toResponseBody("application/json".toMediaType()))
        }

        fun itemDto(id: String, form: String = "CBO_COLLECTION", state: String = "NEEDS_ATTENTION") = SyncAttentionItemDto(
            id = id, form = form, state = state, label = "Label $id", receivedAt = "2026-10-01T10:00:00+00:00",
            submittedBy = "cbo_test_user", syncAttempts = 3, lastAttemptAt = "2026-10-01T10:01:00+00:00", error = "Foodspace answered 422 (Unprocessable Entity)"
        )

        fun detailDto(id: String = "c1", state: String = "NEEDS_ATTENTION") = SyncRecordDetailDto(
            id = id, form = "CBO_COLLECTION", state = state, label = "Label $id", receivedAt = "2026-10-01T10:00:00+00:00",
            submittedBy = "cbo_test_user", syncAttempts = 3, lastAttemptAt = "2026-10-01T10:01:00+00:00", nextAttemptAt = null,
            error = "Foodspace answered 422 (Unprocessable Entity)", duplicateOf = null, canRetry = true, canDismiss = true, history = emptyList()
        )
    }

    // ── the list ───────────────────────────────────────────────────────────────────

    @Test
    fun theList_isReadIntoModels_withTimesAndKinds() = runTest {
        api.attention = { _, _ -> ok(SyncAttentionPageDto(listOf(itemDto("c1"), itemDto("d1", form = "VETTING_DECISION", state = "DUPLICATE_HELD")), 1, 100, 2, false)) }

        val items = (repository.attention() as AdminResult.Success).value

        assertEquals(listOf("c1", "d1"), items.map { it.id })
        assertEquals(listOf(SyncForm.CBO_COLLECTION, SyncForm.VETTING_DECISION), items.map { it.form })
        assertEquals(listOf(SyncState.NEEDS_ATTENTION, SyncState.DUPLICATE_HELD), items.map { it.state })
        assertEquals(parseIsoInstantMillis("2026-10-01T10:00:00+00:00"), items[0].receivedAtMillis)
        assertEquals(3, items[0].attempts)
        assertEquals("Foodspace answered 422 (Unprocessable Entity)", items[0].error)
        assertEquals("cbo_test_user", items[0].submittedBy)
    }

    @Test
    fun everyPageIsFetched_untilTheServerSaysThereIsNoMore_inTheOrderGiven() = runTest {
        api.attention = { page, _ ->
            when (page) {
                1 -> ok(SyncAttentionPageDto(listOf(itemDto("a"), itemDto("b")), 1, 2, 5, hasMore = true))
                2 -> ok(SyncAttentionPageDto(listOf(itemDto("c"), itemDto("d")), 2, 2, 5, hasMore = true))
                else -> ok(SyncAttentionPageDto(listOf(itemDto("e")), 3, 2, 5, hasMore = false))
            }
        }

        val items = (repository.attention() as AdminResult.Success).value

        assertEquals(listOf("a", "b", "c", "d", "e"), items.map { it.id })
        assertEquals(listOf(1, 2, 3), api.attentionRequests.map { it.first })
        assertTrue(api.attentionRequests.all { it.second == AdminSyncResolutionRepositoryImpl.PAGE_SIZE })
    }

    @Test
    fun aRunawayList_stopsAtThePageLimit_insteadOfLoopingForever() = runTest {
        api.attention = { page, _ -> ok(SyncAttentionPageDto(listOf(itemDto("p$page")), page, 100, 9_999, hasMore = true)) }

        val items = (repository.attention() as AdminResult.Success).value

        assertEquals(AdminSyncResolutionRepositoryImpl.MAX_PAGES, items.size)
    }

    @Test
    fun aFailureOnALaterPage_failsTheWholeList_ratherThanShowingAPartialOne() = runTest {
        api.attention = { page, _ -> if (page == 1) ok(SyncAttentionPageDto(listOf(itemDto("a")), 1, 1, 2, hasMore = true)) else error(500) }

        assertEquals(AdminResult.Failed, repository.attention())
    }

    @Test
    fun anEmptyList_isSuccess() = runTest {
        assertEquals(emptyList<AttentionItem>(), (repository.attention() as AdminResult.Success).value)
    }

    @Test
    fun aKindOrStateTheAppDoesNotKnow_doesNotBreakReading() = runTest {
        api.attention = { _, _ -> ok(SyncAttentionPageDto(listOf(itemDto("x", form = "SOMETHING_NEW", state = "SOME_FUTURE_STATE")))) }

        val item = (repository.attention() as AdminResult.Success).value.single()

        assertEquals(SyncState.UNKNOWN, item.state)
    }

    // ── one record ─────────────────────────────────────────────────────────────────

    @Test
    fun aRecord_isReadWithItsErrorAttemptsDuplicateAndHistory() = runTest {
        api.record = {
            ok(
                detailDto("dup", state = "DUPLICATE_HELD").copy(
                    nextAttemptAt = "2026-10-01T12:00:00+00:00",
                    duplicateOf = DuplicateOfDto("orig", "Donor orig", "FORWARDED", "2026-10-01T09:00:00+00:00"),
                    history = listOf(AdminActionEntryDto("2026-10-01T11:00:00+00:00", "admin_test_user", "DISMISS", "Same note", "DISMISSED"))
                )
            )
        }

        val detail = (repository.record("dup", SyncForm.CBO_COLLECTION) as AdminResult.Success).value

        assertEquals(SyncState.DUPLICATE_HELD, detail.state)
        assertEquals("Foodspace answered 422 (Unprocessable Entity)", detail.error)
        assertEquals(3, detail.attempts)
        assertEquals(parseIsoInstantMillis("2026-10-01T12:00:00+00:00"), detail.nextAttemptMillis)
        assertEquals(DuplicateOf("orig", "Donor orig", SyncState.FORWARDED, parseIsoInstantMillis("2026-10-01T09:00:00+00:00")), detail.duplicateOf)
        assertTrue(detail.canRetry && detail.canDismiss)
        val entry = detail.history.single()
        assertEquals("admin_test_user", entry.admin)
        assertEquals("DISMISS", entry.action)
        assertEquals("Same note", entry.reason)
        assertEquals(parseIsoInstantMillis("2026-10-01T11:00:00+00:00"), entry.atMillis)
    }

    @Test
    fun theKindIsSentWithTheRequest_asItsServerName() = runTest {
        repository.record("d1", SyncForm.VETTING_DECISION)

        assertEquals(listOf("d1" to "VETTING_DECISION"), api.recordRequests)
    }

    // ── actions ────────────────────────────────────────────────────────────────────

    @Test
    fun aRetry_returnsWhereTheRecordWasAndWhereItIsNow() = runTest {
        val resolution = (repository.retry("c1", SyncForm.CBO_COLLECTION) as AdminResult.Success).value

        assertEquals(SyncState.NEEDS_ATTENTION, resolution.previousState)
        assertEquals(SyncState.FORWARDED, resolution.record.state)
        assertEquals(listOf("c1" to "CBO_COLLECTION"), api.retryRequests)
    }

    @Test
    fun aDismissal_sendsTheReasonAndTheKind() = runTest {
        val resolution = (repository.dismiss("d1", SyncForm.VETTING_DECISION, "Beneficiary withdrawn") as AdminResult.Success).value

        assertEquals(SyncState.DISMISSED, resolution.record.state)
        assertEquals(listOf(Triple("d1", "VETTING_DECISION", "Beneficiary withdrawn")), api.dismissRequests)
    }

    // ── what the server can answer ─────────────────────────────────────────────────

    @Test
    fun noConnection_isOffline_forEveryCall() = runTest {
        api.attention = { _, _ -> throw IOException("no route") }
        api.record = { throw IOException("no route") }
        api.retry = { throw IOException("no route") }
        api.dismiss = { throw IOException("no route") }

        assertEquals(AdminResult.Offline, repository.attention())
        assertEquals(AdminResult.Offline, repository.record("c1", SyncForm.CBO_COLLECTION))
        assertEquals(AdminResult.Offline, repository.retry("c1", SyncForm.CBO_COLLECTION))
        assertEquals(AdminResult.Offline, repository.dismiss("c1", SyncForm.CBO_COLLECTION, "x"))
    }

    @Test
    fun aRefusedAccount_isDenied() = runTest {
        api.retry = { error(403) }
        assertEquals(AdminResult.Denied, repository.retry("c1", SyncForm.CBO_COLLECTION))
        api.retry = { error(401) }
        assertEquals(AdminResult.Denied, repository.retry("c1", SyncForm.CBO_COLLECTION))
    }

    @Test
    fun anUnknownRecord_isNotFound() = runTest {
        api.record = { error(404) }

        assertEquals(AdminResult.NotFound, repository.record("nope", SyncForm.CBO_COLLECTION))
    }

    @Test
    fun aConflict_carriesTheServersOwnWords() = runTest {
        api.retry = { error(409, "Foodspace already has this record.") }

        val result = repository.retry("c1", SyncForm.CBO_COLLECTION)

        assertEquals(AdminResult.Conflict("Foodspace already has this record."), result)
    }

    @Test
    fun aRefusedRequest_carriesTheServersOwnWords() = runTest {
        api.dismiss = { error(400, "A reason is required: a dismissed record is never sent to Foodspace.") }

        val result = repository.dismiss("c1", SyncForm.CBO_COLLECTION, " ")

        assertEquals(AdminResult.Invalid("A reason is required: a dismissed record is never sent to Foodspace."), result)
    }

    @Test
    fun aConflictWithAnUnreadableBody_stillIsAConflict_withNoMessage() = runTest {
        api.retry = { Response.error(409, "<html>oops</html>".toResponseBody("text/html".toMediaType())) }

        assertEquals(AdminResult.Conflict(null), repository.retry("c1", SyncForm.CBO_COLLECTION))
    }

    @Test
    fun aServerErrorOrUnreadableAnswer_isFailed() = runTest {
        api.retry = { error(500) }
        assertEquals(AdminResult.Failed, repository.retry("c1", SyncForm.CBO_COLLECTION))
        api.retry = { throw JsonParseException("bad json") }
        assertEquals(AdminResult.Failed, repository.retry("c1", SyncForm.CBO_COLLECTION))
        api.retry = { ok<ResolutionDto?>(null) as Response<ApiEnvelope<ResolutionDto>> }
        assertEquals(AdminResult.Failed, repository.retry("c1", SyncForm.CBO_COLLECTION))
    }
}
