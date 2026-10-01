package com.example.client.ui.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.client.R
import com.example.client.data.repository.AdminActionEntry
import com.example.client.data.repository.AdminResult
import com.example.client.data.repository.AdminSyncResolutionRepository
import com.example.client.data.repository.DuplicateOf
import com.example.client.data.repository.RecordDetail
import com.example.client.data.repository.Resolution
import com.example.client.data.repository.SyncForm
import com.example.client.data.repository.SyncState
import com.example.client.ui.components.FilledPillButton
import com.example.client.ui.components.OutlinePillButton
import com.example.client.ui.components.SaTextArea
import com.example.client.ui.components.ScreenHeader
import com.example.client.ui.components.TagBadge
import com.example.client.ui.theme.CBOCollectorTheme
import com.example.client.ui.theme.Figtree
import com.example.client.ui.theme.Poppins
import com.example.client.ui.theme.SaColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Navigation arguments of the record screen: the record's id, and which kind of record it is ([SyncForm] name). */
const val FAILED_SYNC_ID_ARG = "id"
const val FAILED_SYNC_FORM_ARG = "form"

object FailedSyncDetailTags {
    const val SCREEN = "failed_sync_detail"
    const val STATE = "failed_sync_detail_state"
    const val ERROR = "failed_sync_detail_error"
    const val OUTCOME = "failed_sync_detail_outcome"
    const val NOTICE = "failed_sync_detail_notice"
    const val LOADING = "failed_sync_detail_loading"
    const val DUPLICATE = "failed_sync_detail_duplicate"
    const val RETRY = "failed_sync_detail_retry"
    const val DISMISS = "failed_sync_detail_dismiss"
    const val REASON = "failed_sync_detail_reason"
    const val DISMISS_CONFIRM = "failed_sync_detail_dismiss_confirm"
    const val DISMISS_CANCEL = "failed_sync_detail_dismiss_cancel"
    const val NO_ACTIONS = "failed_sync_detail_no_actions"
    const val HISTORY = "failed_sync_detail_history"
}

/** What a retry or dismissal came to, in the words an Admin needs. */
enum class ActionOutcome { SENT, STILL_RETRYING, REJECTED_AGAIN, SUPERSEDED, DISMISSED, OTHER }

/** Why the last load or action did not do what was asked. None of these changes the record on the server. */
enum class DetailNotice { OFFLINE, DENIED, FAILED, NOT_FOUND, CONFLICT, INVALID, ACTION_OFFLINE, ACTION_FAILED }

data class FailedSyncDetailUiState(
    val loading: Boolean = true,
    val detail: RecordDetail? = null,
    /** A retry or dismissal is in flight; both buttons wait. */
    val busy: Boolean = false,
    /** The reason box is open. */
    val dismissing: Boolean = false,
    val reason: String = "",
    val outcome: ActionOutcome? = null,
    val notice: DetailNotice? = null,
    /** The server's own wording for a [DetailNotice.CONFLICT] or [DetailNotice.INVALID], when it gave one. */
    val serverMessage: String? = null
) {
    val canConfirmDismiss: Boolean get() = !busy && reason.isNotBlank()
}

fun outcomeOf(resolution: Resolution): ActionOutcome = when (resolution.record.state) {
    SyncState.FORWARDED -> ActionOutcome.SENT
    SyncState.RETRYING -> ActionOutcome.STILL_RETRYING
    SyncState.NEEDS_ATTENTION -> ActionOutcome.REJECTED_AGAIN
    SyncState.SUPERSEDED -> ActionOutcome.SUPERSEDED
    SyncState.DISMISSED -> ActionOutcome.DISMISSED
    else -> ActionOutcome.OTHER
}

/**
 * One record that would not sync: why, what has been done about it, and the two things an Admin can do. Everything goes
 * through the server, so a failure changes nothing and says so; the record on screen is only ever replaced by the
 * server's own answer.
 */
@HiltViewModel
class FailedSyncDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: AdminSyncResolutionRepository
) : ViewModel() {

    private val id: String = checkNotNull(savedStateHandle[FAILED_SYNC_ID_ARG]) { "The record id is a required navigation argument." }
    private val form: SyncForm = SyncForm.valueOf(
        checkNotNull(savedStateHandle[FAILED_SYNC_FORM_ARG]) { "The record kind is a required navigation argument." }
    )

    private val _uiState = MutableStateFlow(FailedSyncDetailUiState())
    val uiState: StateFlow<FailedSyncDetailUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    fun load() {
        _uiState.update { it.copy(loading = true) }
        viewModelScope.launch {
            when (val result = repository.record(id, form)) {
                is AdminResult.Success -> _uiState.update { it.copy(loading = false, detail = result.value, notice = null, serverMessage = null) }
                else -> _uiState.update { it.copy(loading = false, notice = result.loadNotice(), serverMessage = null) }
            }
        }
    }

    fun retry() {
        val state = _uiState.value
        if (state.busy || state.detail?.canRetry != true) return
        act { repository.retry(id, form) }
    }

    fun startDismiss() = _uiState.update { if (it.busy || it.detail?.canDismiss != true) it else it.copy(dismissing = true) }

    fun cancelDismiss() = _uiState.update { it.copy(dismissing = false, reason = "") }

    fun onReasonChange(value: String) = _uiState.update { it.copy(reason = value.take(MAX_REASON_LENGTH)) }

    fun confirmDismiss() {
        val state = _uiState.value
        if (!state.canConfirmDismiss || !state.dismissing || state.detail?.canDismiss != true) return
        act { repository.dismiss(id, form, state.reason.trim()) }
    }

    private fun act(call: suspend () -> AdminResult<Resolution>) {
        _uiState.update { it.copy(busy = true, outcome = null, notice = null, serverMessage = null) }
        viewModelScope.launch {
            when (val result = call()) {
                is AdminResult.Success -> _uiState.update {
                    it.copy(busy = false, detail = result.value.record, outcome = outcomeOf(result.value), dismissing = false, reason = "")
                }
                else -> {
                    _uiState.update { it.copy(busy = false, notice = result.actionNotice(), serverMessage = result.message()) }
                    // The record changed under us (e.g. someone else dealt with it): show it as it is now, keeping the explanation.
                    if (result is AdminResult.Conflict || result == AdminResult.NotFound) refreshQuietly()
                }
            }
        }
    }

    private suspend fun refreshQuietly() {
        (repository.record(id, form) as? AdminResult.Success)?.let { fresh ->
            _uiState.update { it.copy(detail = fresh.value, dismissing = false, reason = "") }
        }
    }

    private fun AdminResult<*>.loadNotice(): DetailNotice = when (this) {
        AdminResult.Offline -> DetailNotice.OFFLINE
        AdminResult.Denied -> DetailNotice.DENIED
        AdminResult.NotFound -> DetailNotice.NOT_FOUND
        else -> DetailNotice.FAILED
    }

    private fun AdminResult<*>.actionNotice(): DetailNotice = when (this) {
        AdminResult.Offline -> DetailNotice.ACTION_OFFLINE
        AdminResult.Denied -> DetailNotice.DENIED
        AdminResult.NotFound -> DetailNotice.NOT_FOUND
        is AdminResult.Conflict -> DetailNotice.CONFLICT
        is AdminResult.Invalid -> DetailNotice.INVALID
        else -> DetailNotice.ACTION_FAILED
    }

    private fun AdminResult<*>.message(): String? = when (this) {
        is AdminResult.Conflict -> message
        is AdminResult.Invalid -> message
        else -> null
    }

    companion object {
        /** The server's limit for a dismissal reason. */
        const val MAX_REASON_LENGTH = 500
    }
}

// ── Screen ─────────────────────────────────────────────────────────────────────

@Composable
fun FailedSyncDetailRoute(onBack: () -> Unit, viewModel: FailedSyncDetailViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()
    FailedSyncDetailScreen(
        state, onBack = onBack, onRetry = viewModel::retry, onStartDismiss = viewModel::startDismiss,
        onCancelDismiss = viewModel::cancelDismiss, onReasonChange = viewModel::onReasonChange, onConfirmDismiss = viewModel::confirmDismiss
    )
}

@Composable
fun FailedSyncDetailScreen(
    state: FailedSyncDetailUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onStartDismiss: () -> Unit,
    onCancelDismiss: () -> Unit,
    onReasonChange: (String) -> Unit,
    onConfirmDismiss: () -> Unit
) = CBOCollectorTheme {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SaColors.Surface)
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
            .testTag(FailedSyncDetailTags.SCREEN),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        ScreenHeader(title = stringResource(R.string.admin_failed_detail_title), onBack = onBack)

        state.outcome?.let { Outcome(it) }
        state.notice?.let { Notice(it, state.serverMessage) }

        val detail = state.detail
        if (detail == null) {
            if (state.loading) {
                Text(
                    stringResource(R.string.admin_failed_loading),
                    fontFamily = Figtree, fontSize = 14.sp, color = SaColors.Muted,
                    modifier = Modifier.testTag(FailedSyncDetailTags.LOADING)
                )
            }
        } else {
            WhatHappened(detail)
            detail.duplicateOf?.let { DuplicateCard(it) }
            Actions(detail, state, onRetry, onStartDismiss, onCancelDismiss, onReasonChange, onConfirmDismiss)
            if (detail.history.isNotEmpty()) History(detail.history)
        }
    }
}

@Composable
private fun Outcome(outcome: ActionOutcome) {
    val text = stringResource(
        when (outcome) {
            ActionOutcome.SENT -> R.string.admin_failed_outcome_sent
            ActionOutcome.STILL_RETRYING -> R.string.admin_failed_outcome_retrying
            ActionOutcome.REJECTED_AGAIN -> R.string.admin_failed_outcome_rejected
            ActionOutcome.SUPERSEDED -> R.string.admin_failed_outcome_superseded
            ActionOutcome.DISMISSED -> R.string.admin_failed_outcome_dismissed
            ActionOutcome.OTHER -> R.string.admin_failed_outcome_other
        }
    )
    val good = outcome == ActionOutcome.SENT || outcome == ActionOutcome.DISMISSED
    Text(
        text,
        fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 19.sp,
        color = if (good) SaColors.TagOkText else SaColors.TagWarnText,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (good) SaColors.TagOkBg else SaColors.TagWarnBg)
            .padding(14.dp)
            .testTag(FailedSyncDetailTags.OUTCOME)
    )
}

@Composable
private fun Notice(notice: DetailNotice, serverMessage: String?) {
    val text = when (notice) {
        // The server's own wording is specific ("Foodspace already has this record."), so it is used when there is one.
        DetailNotice.CONFLICT -> serverMessage ?: stringResource(R.string.admin_failed_notice_conflict)
        DetailNotice.INVALID -> serverMessage ?: stringResource(R.string.admin_failed_notice_invalid)
        DetailNotice.OFFLINE -> stringResource(R.string.admin_failed_notice_offline)
        DetailNotice.DENIED -> stringResource(R.string.admin_failed_notice_denied)
        DetailNotice.FAILED -> stringResource(R.string.admin_failed_notice_failed)
        DetailNotice.NOT_FOUND -> stringResource(R.string.admin_failed_notice_not_found)
        DetailNotice.ACTION_OFFLINE -> stringResource(R.string.admin_failed_notice_action_offline)
        DetailNotice.ACTION_FAILED -> stringResource(R.string.admin_failed_notice_action_failed)
    }
    Banner(text, Modifier.testTag(FailedSyncDetailTags.NOTICE))
}

@Composable
private fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SaColors.White)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) { content() }
}

@Composable
private fun SectionTitle(text: String) =
    Text(text, fontFamily = Poppins, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = SaColors.Ink)

@Composable
private fun Fact(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
        Text(label, fontFamily = Figtree, fontSize = 13.sp, color = SaColors.MutedLight, modifier = Modifier.weight(0.45f))
        Text(value, fontFamily = Figtree, fontSize = 13.sp, color = SaColors.Ink, modifier = Modifier.weight(0.55f))
    }
}

@Composable
private fun WhatHappened(detail: RecordDetail) {
    val notRecorded = stringResource(R.string.admin_failed_not_recorded)
    Card {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                detail.label,
                fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 15.5.sp, color = SaColors.Ink,
                modifier = Modifier.weight(1f)
            )
            TagBadge(stringResource(detail.state.labelRes()), detail.state.palette(), Modifier.testTag(FailedSyncDetailTags.STATE))
        }
        Text(stringResource(detail.form.labelRes()), fontFamily = Figtree, fontSize = 12.sp, color = SaColors.MutedLight)

        SectionTitle(stringResource(R.string.admin_failed_section_what))
        Text(
            detail.error ?: stringResource(R.string.admin_failed_no_error),
            fontFamily = Figtree, fontSize = 14.sp, lineHeight = 21.sp, color = SaColors.Ink,
            modifier = Modifier.testTag(FailedSyncDetailTags.ERROR)
        )
        Fact(stringResource(R.string.admin_failed_fact_received), formatWhen(detail.receivedAtMillis) ?: notRecorded)
        Fact(stringResource(R.string.admin_failed_fact_submitted_by), detail.submittedBy ?: notRecorded)
        Fact(stringResource(R.string.admin_failed_fact_attempts), detail.attempts.toString())
        Fact(stringResource(R.string.admin_failed_fact_last), formatWhen(detail.lastAttemptMillis) ?: notRecorded)
        detail.nextAttemptMillis?.let { Fact(stringResource(R.string.admin_failed_fact_next), formatWhen(it) ?: notRecorded) }
    }
}

@Composable
private fun DuplicateCard(original: DuplicateOf) {
    Card(Modifier.testTag(FailedSyncDetailTags.DUPLICATE).semantics(mergeDescendants = true) {}) {
        SectionTitle(stringResource(R.string.admin_failed_section_duplicate))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(original.label, fontFamily = Figtree, fontSize = 14.sp, color = SaColors.Ink, modifier = Modifier.weight(1f))
            TagBadge(stringResource(original.state.labelRes()), original.state.palette())
        }
        formatWhen(original.receivedAtMillis)?.let {
            Text(stringResource(R.string.admin_failed_fact_received) + ": " + it, fontFamily = Figtree, fontSize = 12.sp, color = SaColors.MutedLight)
        }
    }
}

@Composable
private fun Actions(
    detail: RecordDetail,
    state: FailedSyncDetailUiState,
    onRetry: () -> Unit,
    onStartDismiss: () -> Unit,
    onCancelDismiss: () -> Unit,
    onReasonChange: (String) -> Unit,
    onConfirmDismiss: () -> Unit
) {
    Card {
        SectionTitle(stringResource(R.string.admin_failed_section_actions))

        if (!detail.canRetry && !detail.canDismiss) {
            Text(
                stringResource(R.string.admin_failed_nothing_to_do),
                fontFamily = Figtree, fontSize = 13.sp, lineHeight = 19.sp, color = SaColors.Muted,
                modifier = Modifier.testTag(FailedSyncDetailTags.NO_ACTIONS)
            )
        } else {
            ActionButtons(detail, state, onRetry, onStartDismiss, onCancelDismiss, onReasonChange, onConfirmDismiss)
        }
    }
}

@Composable
private fun ActionButtons(
    detail: RecordDetail,
    state: FailedSyncDetailUiState,
    onRetry: () -> Unit,
    onStartDismiss: () -> Unit,
    onCancelDismiss: () -> Unit,
    onReasonChange: (String) -> Unit,
    onConfirmDismiss: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (detail.canRetry) {
            val release = detail.state == SyncState.DUPLICATE_HELD
            FilledPillButton(
                onClick = { if (!state.busy) onRetry() },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth().testTag(FailedSyncDetailTags.RETRY)
            ) {
                Text(
                    stringResource(
                        when {
                            state.busy -> R.string.admin_failed_retry_busy
                            release -> R.string.admin_failed_release
                            else -> R.string.admin_failed_retry
                        }
                    ),
                    fontFamily = Figtree, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = SaColors.Ink
                )
            }
            Hint(stringResource(if (release) R.string.admin_failed_release_hint else R.string.admin_failed_retry_hint))
        }

        if (detail.canDismiss) {
            if (!state.dismissing) {
                OutlinePillButton(
                    onClick = { if (!state.busy) onStartDismiss() },
                    modifier = Modifier.fillMaxWidth().testTag(FailedSyncDetailTags.DISMISS)
                ) {
                    Text(stringResource(R.string.admin_failed_dismiss), fontFamily = Figtree, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = SaColors.Ink)
                }
                Hint(stringResource(R.string.admin_failed_dismiss_hint))
            } else {
                Text(
                    stringResource(R.string.admin_failed_dismiss_reason_label),
                    fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = SaColors.Ink
                )
                SaTextArea(
                    value = state.reason,
                    onValueChange = onReasonChange,
                    placeholder = stringResource(R.string.admin_failed_dismiss_reason_placeholder),
                    minLines = 3,
                    fieldTestTag = FailedSyncDetailTags.REASON
                )
                FilledPillButton(
                    onClick = { if (state.canConfirmDismiss) onConfirmDismiss() },
                    enabled = state.canConfirmDismiss,
                    modifier = Modifier.fillMaxWidth().testTag(FailedSyncDetailTags.DISMISS_CONFIRM)
                ) {
                    Text(stringResource(R.string.admin_failed_dismiss_confirm), fontFamily = Figtree, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = SaColors.Ink)
                }
                OutlinePillButton(
                    onClick = { if (!state.busy) onCancelDismiss() },
                    modifier = Modifier.fillMaxWidth().testTag(FailedSyncDetailTags.DISMISS_CANCEL),
                    contentPadding = PaddingValues(vertical = 11.dp, horizontal = 22.dp)
                ) {
                    Text(stringResource(R.string.admin_failed_dismiss_cancel), fontFamily = Figtree, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = SaColors.Ink)
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String) =
    Text(text, fontFamily = Figtree, fontSize = 12.sp, lineHeight = 17.sp, color = SaColors.MutedLight)

@Composable
private fun History(history: List<AdminActionEntry>) {
    Card(Modifier.testTag(FailedSyncDetailTags.HISTORY)) {
        SectionTitle(stringResource(R.string.admin_failed_section_history))
        history.forEach { entry ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.semantics(mergeDescendants = true) {}) {
                Text(
                    stringResource(R.string.admin_failed_history_entry, actionText(entry.action) + " · " + resultText(entry.resultStatus), entry.admin),
                    fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = SaColors.Ink
                )
                formatWhen(entry.atMillis)?.let { Text(it, fontFamily = Figtree, fontSize = 12.sp, color = SaColors.MutedLight) }
                entry.reason?.let { Text(it, fontFamily = Figtree, fontSize = 13.sp, lineHeight = 19.sp, color = SaColors.Muted) }
            }
        }
    }
}

@Composable
private fun actionText(action: String) =
    stringResource(if (action == "DISMISS") R.string.admin_failed_history_dismiss else R.string.admin_failed_history_retry)

@Composable
private fun resultText(resultStatus: String) = stringResource(
    when (resultStatus) {
        "FORWARDED" -> R.string.admin_failed_history_result_forwarded
        "SYNCED_LOCAL_PENDING_FOODSPACE", "PENDING" -> R.string.admin_failed_history_result_failing
        "DISMISSED" -> R.string.admin_failed_history_result_dismissed
        else -> R.string.admin_failed_history_result_other
    }
)
