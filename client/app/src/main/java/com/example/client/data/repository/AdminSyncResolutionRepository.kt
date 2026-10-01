package com.example.client.data.repository

import com.example.client.data.parseIsoInstantMillis
import com.example.client.network.AdminActionEntryDto
import com.example.client.network.AdminApiService
import com.example.client.network.DismissRequestDto
import com.example.client.network.DuplicateOfDto
import com.example.client.network.ResolutionDto
import com.example.client.network.SyncAttentionItemDto
import com.example.client.network.SyncRecordDetailDto
import javax.inject.Inject
import javax.inject.Singleton

/** Which kind of record. The names are the server's wire values (`form` in the Admin API). */
enum class SyncForm { CBO_COLLECTION, VETTING_DECISION }

/** Where a record stands with Foodspace, as the server reports it. [UNKNOWN] is a state a newer server added. */
enum class SyncState { WAITING, RETRYING, NEEDS_ATTENTION, FORWARDED, DUPLICATE_HELD, SUPERSEDED, DISMISSED, UNKNOWN }

/** A record that needs an Admin: rejected by Foodspace, retries used up, or a held suspected duplicate. */
data class AttentionItem(
    val id: String,
    val form: SyncForm,
    val state: SyncState,
    val label: String,
    val receivedAtMillis: Long?,
    val submittedBy: String?,
    val attempts: Int,
    val error: String?
)

data class DuplicateOf(val id: String, val label: String, val state: SyncState, val receivedAtMillis: Long?)

/** One entry of a record's audit trail. [action] is RETRY or DISMISS; [resultStatus] the record's forwarding status afterwards. */
data class AdminActionEntry(val atMillis: Long?, val admin: String, val action: String, val reason: String?, val resultStatus: String)

data class RecordDetail(
    val id: String,
    val form: SyncForm,
    val state: SyncState,
    val label: String,
    val receivedAtMillis: Long?,
    val submittedBy: String?,
    val attempts: Int,
    val lastAttemptMillis: Long?,
    val nextAttemptMillis: Long?,
    val error: String?,
    val duplicateOf: DuplicateOf?,
    val canRetry: Boolean,
    val canDismiss: Boolean,
    val history: List<AdminActionEntry>
)

/** The answer to a retry or dismissal: the state the record was in, and the record as it is now. */
data class Resolution(val previousState: SyncState, val record: RecordDetail)

/** What came back from the server for one Admin call. Only [Success] carries data. */
sealed interface AdminResult<out T> {
    data class Success<T>(val value: T) : AdminResult<T>

    /** No connection. These calls need the server, so there is nothing to fall back to. */
    object Offline : AdminResult<Nothing>

    /** The server refused this account (not an Admin, or the session ended). */
    object Denied : AdminResult<Nothing>

    /** No such record any more. */
    object NotFound : AdminResult<Nothing>

    /** The record is not in a state that allows this (e.g. already sent). [message] is the server's own, safe to show. */
    data class Conflict(val message: String?) : AdminResult<Nothing>

    /** The request was refused as invalid (e.g. no reason given). [message] is the server's own, safe to show. */
    data class Invalid(val message: String?) : AdminResult<Nothing>

    /** Anything else (server error, bad response). */
    object Failed : AdminResult<Nothing>
}

/** Looking at, retrying and dismissing the records that would not sync (backend #50). Every call needs the server. */
interface AdminSyncResolutionRepository {
    /** Every record that needs an Admin, oldest first. */
    suspend fun attention(): AdminResult<List<AttentionItem>>

    suspend fun record(id: String, form: SyncForm): AdminResult<RecordDetail>

    /** Sends the record to Foodspace now. The result's record says how it went. */
    suspend fun retry(id: String, form: SyncForm): AdminResult<Resolution>

    /** Marks the record as never to be sent. [reason] is required and kept in the server's audit trail. */
    suspend fun dismiss(id: String, form: SyncForm, reason: String): AdminResult<Resolution>
}

@Singleton
class AdminSyncResolutionRepositoryImpl @Inject constructor(
    private val api: AdminApiService
) : AdminSyncResolutionRepository {

    override suspend fun attention(): AdminResult<List<AttentionItem>> {
        val items = ArrayList<AttentionItem>()
        var page = 1
        while (true) {
            val result = adminCall { api.getAttention(page, PAGE_SIZE) }
            val data = when (result) {
                is AdminResult.Success -> result.value
                else -> return result.failure()
            }
            items += data.items.map { it.toModel() }
            if (!data.hasMore) break
            if (++page > MAX_PAGES) break // a runaway list must not loop forever; the first pages are the oldest, the ones that matter
        }
        return AdminResult.Success(items)
    }

    override suspend fun record(id: String, form: SyncForm): AdminResult<RecordDetail> =
        adminCall { api.getSyncRecord(id, form.name) }.map { it.toModel() }

    override suspend fun retry(id: String, form: SyncForm): AdminResult<Resolution> =
        adminCall { api.retrySync(id, form.name) }.map { it.toModel() }

    override suspend fun dismiss(id: String, form: SyncForm, reason: String): AdminResult<Resolution> =
        adminCall { api.dismissSync(id, form.name, DismissRequestDto(reason)) }.map { it.toModel() }

    // ── mapping ────────────────────────────────────────────────────────────────────

    private fun String.toForm(): SyncForm = SyncForm.values().firstOrNull { it.name == this } ?: SyncForm.CBO_COLLECTION

    private fun String.toState(): SyncState = SyncState.values().firstOrNull { it.name == this } ?: SyncState.UNKNOWN

    private fun SyncAttentionItemDto.toModel() = AttentionItem(
        id = id, form = form.toForm(), state = state.toState(), label = label,
        receivedAtMillis = parseIsoInstantMillis(receivedAt), submittedBy = submittedBy, attempts = syncAttempts, error = error
    )

    private fun DuplicateOfDto.toModel() = DuplicateOf(id, label, state.toState(), parseIsoInstantMillis(receivedAt))

    private fun AdminActionEntryDto.toModel() = AdminActionEntry(parseIsoInstantMillis(at), admin, action, reason, resultStatus)

    private fun SyncRecordDetailDto.toModel() = RecordDetail(
        id = id, form = form.toForm(), state = state.toState(), label = label,
        receivedAtMillis = parseIsoInstantMillis(receivedAt), submittedBy = submittedBy, attempts = syncAttempts,
        lastAttemptMillis = parseIsoInstantMillis(lastAttemptAt), nextAttemptMillis = parseIsoInstantMillis(nextAttemptAt),
        error = error, duplicateOf = duplicateOf?.toModel(), canRetry = canRetry, canDismiss = canDismiss,
        history = history.map { it.toModel() }
    )

    private fun ResolutionDto.toModel() = Resolution(previousState.toState(), record.toModel())

    companion object {
        /** The most the backend allows per page, so the fewest round trips. */
        const val PAGE_SIZE = 100
        const val MAX_PAGES = 20
    }
}
