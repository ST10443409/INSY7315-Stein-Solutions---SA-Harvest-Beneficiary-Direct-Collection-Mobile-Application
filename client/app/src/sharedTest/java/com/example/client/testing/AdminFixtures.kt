package com.example.client.testing

import com.example.client.data.repository.AdminActionEntry
import com.example.client.data.repository.AdminResult
import com.example.client.data.repository.AdminSyncResolutionRepository
import com.example.client.data.repository.AttentionItem
import com.example.client.data.repository.DuplicateOf
import com.example.client.data.repository.RecordDetail
import com.example.client.data.repository.Resolution
import com.example.client.data.repository.SyncForm
import com.example.client.data.repository.SyncState
import kotlinx.coroutines.CompletableDeferred

// Shared by src/test and src/androidTest: the failed-sync (#50) fixtures.

const val SAMPLE_ERROR = "Foodspace answered 422 (Unprocessable Entity)"

fun sampleAttention(
    id: String = "c1",
    form: SyncForm = SyncForm.CBO_COLLECTION,
    state: SyncState = SyncState.NEEDS_ATTENTION,
    label: String = "Donor $id · delivery note DN-1",
    error: String? = SAMPLE_ERROR,
    attempts: Int = 3,
    submittedBy: String? = "cbo_test_user"
) = AttentionItem(id, form, state, label, receivedAtMillis = 1_700_000_000_000L, submittedBy = submittedBy, attempts = attempts, error = error)

fun sampleDetail(
    id: String = "c1",
    form: SyncForm = SyncForm.CBO_COLLECTION,
    state: SyncState = SyncState.NEEDS_ATTENTION,
    label: String = "Donor $id · delivery note DN-1",
    error: String? = SAMPLE_ERROR,
    attempts: Int = 3,
    nextAttemptMillis: Long? = null,
    duplicateOf: DuplicateOf? = null,
    canRetry: Boolean = state != SyncState.FORWARDED && state != SyncState.SUPERSEDED,
    canDismiss: Boolean = state == SyncState.NEEDS_ATTENTION || state == SyncState.DUPLICATE_HELD,
    history: List<AdminActionEntry> = emptyList()
) = RecordDetail(
    id = id, form = form, state = state, label = label, receivedAtMillis = 1_700_000_000_000L, submittedBy = "cbo_test_user",
    attempts = attempts, lastAttemptMillis = 1_700_000_060_000L, nextAttemptMillis = nextAttemptMillis, error = error,
    duplicateOf = duplicateOf, canRetry = canRetry, canDismiss = canDismiss, history = history
)

fun sampleResolution(previous: SyncState = SyncState.NEEDS_ATTENTION, record: RecordDetail = sampleDetail(state = SyncState.FORWARDED, error = null)) =
    Resolution(previous, record)

class FakeAdminSyncResolutionRepository : AdminSyncResolutionRepository {
    var attentionResult: AdminResult<List<AttentionItem>> = AdminResult.Success(emptyList())
    var recordResult: AdminResult<RecordDetail> = AdminResult.Success(sampleDetail())
    var retryResult: AdminResult<Resolution> = AdminResult.Success(sampleResolution())
    var dismissResult: AdminResult<Resolution> =
        AdminResult.Success(sampleResolution(record = sampleDetail(state = SyncState.DISMISSED, canRetry = true, canDismiss = false)))

    var attentionCalls = 0
    val recordCalls = mutableListOf<Pair<String, SyncForm>>()
    val retryCalls = mutableListOf<Pair<String, SyncForm>>()
    val dismissCalls = mutableListOf<Triple<String, SyncForm, String>>()

    /** When set, a retry or dismissal waits for it, so a test can look at the screen while one is running. */
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun attention(): AdminResult<List<AttentionItem>> {
        attentionCalls++
        return attentionResult
    }

    override suspend fun record(id: String, form: SyncForm): AdminResult<RecordDetail> {
        recordCalls += id to form
        return recordResult
    }

    override suspend fun retry(id: String, form: SyncForm): AdminResult<Resolution> {
        retryCalls += id to form
        gate?.await()
        return retryResult
    }

    override suspend fun dismiss(id: String, form: SyncForm, reason: String): AdminResult<Resolution> {
        dismissCalls += Triple(id, form, reason)
        gate?.await()
        return dismissResult
    }
}
