package com.example.client.ui.vetting

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.client.R
import com.example.client.data.local.entity.DecisionOutcome
import com.example.client.data.local.entity.FoodspaceBeneficiaryRecord
import com.example.client.data.local.entity.VettingDecision
import com.example.client.data.repository.RecordsMeta
import com.example.client.data.repository.RefreshOutcome
import com.example.client.data.repository.VettingRecordsRepository
import com.example.client.data.repository.VettingRepository
import com.example.client.ui.components.CardButton
import com.example.client.ui.components.FilledPillButton
import com.example.client.ui.components.ScreenHeader
import com.example.client.ui.theme.CBOCollectorTheme
import com.example.client.ui.theme.Figtree
import com.example.client.ui.theme.Poppins
import com.example.client.ui.theme.SaColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

object ListTags {
    const val SCREEN = "vetting_list"
    const val LIST = "vetting_list_items"
    const val EMPTY = "vetting_list_empty"
    const val SYNC = "vetting_sync"
    const val NOTICE = "vetting_notice"
    const val LOADING = "vetting_loading"
    fun item(id: String) = "vetting_item_$id"
}

/** Something worth telling the officer after a sync, shown as a banner that never blocks the list. */
enum class ListNotice { OFFLINE, UNAVAILABLE, FAILED, STALE }

data class RecordItem(
    val id: String,
    val legalName: String,
    val province: String,
    val contactName: String,
    /** The officer's current decision on this record, or null if they have not decided. */
    val decision: DecisionOutcome?,
    /** Whether that decision has reached the server yet; null when there is no decision. */
    val decisionSync: DecisionSyncDisplay? = null
)

data class VettingListUiState(
    /** False until the device's cache has answered, so an empty cache is not announced before it is known to be empty. */
    val loaded: Boolean = false,
    val items: List<RecordItem> = emptyList(),
    val refreshing: Boolean = false,
    val notice: ListNotice? = null,
    val meta: RecordsMeta? = null
) {
    val decided: Int get() = items.count { it.decision != null }
}

/**
 * The list reads only the device's cache, so it is usable offline. It asks the backend for fresh records when it
 * opens and when the officer taps Sync; a failure only ever adds a banner, it never hides or blocks the records.
 */
@HiltViewModel
class VettingListViewModel @Inject constructor(
    private val records: VettingRecordsRepository,
    vetting: VettingRepository
) : ViewModel() {

    private data class Refresh(val running: Boolean = false, val outcome: RefreshOutcome? = null)

    private val refresh = MutableStateFlow(Refresh())

    val uiState: StateFlow<VettingListUiState> = combine(
        records.observeRecords(),
        vetting.observeDecisions(),
        records.meta,
        refresh
    ) { cached, decisions, meta, refresh ->
        val latest = latestDecisionByRecord(decisions)
        VettingListUiState(
            loaded = true,
            items = cached.map { it.toItem(latest[it.id]) },
            refreshing = refresh.running,
            notice = noticeFor(refresh.outcome, meta),
            meta = meta
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), VettingListUiState())

    init {
        refresh()
    }

    fun refresh() {
        if (refresh.value.running) return
        refresh.update { Refresh(running = true, outcome = it.outcome) }
        viewModelScope.launch {
            val outcome = records.refresh()
            refresh.value = Refresh(running = false, outcome = outcome)
        }
    }

    private fun noticeFor(outcome: RefreshOutcome?, meta: RecordsMeta?): ListNotice? = when (outcome) {
        RefreshOutcome.OFFLINE -> ListNotice.OFFLINE
        RefreshOutcome.SERVER_UNAVAILABLE -> ListNotice.UNAVAILABLE
        RefreshOutcome.FAILED -> ListNotice.FAILED
        else -> if (meta?.stale == true) ListNotice.STALE else null
    }

    private fun FoodspaceBeneficiaryRecord.toItem(decision: VettingDecision?) =
        RecordItem(
            id = id, legalName = legalName, province = province, contactName = contactName,
            decision = decision?.outcome, decisionSync = decision?.syncDisplay()
        )
}

/** The officer's current decision on each record: the newest one (several can exist if they changed their mind). */
fun latestDecisionByRecord(decisions: List<VettingDecision>): Map<String, VettingDecision> =
    decisions.groupBy { it.foodspaceRecordId }
        .mapValues { (_, all) -> all.maxWith(compareBy({ it.decisionTimestamp }, { it.createdAt })) }

// ── Screen ─────────────────────────────────────────────────────────────────────

@Composable
fun VettingListRoute(onOpen: (String) -> Unit, viewModel: VettingListViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()
    VettingListScreen(state, onSync = viewModel::refresh, onOpen = onOpen)
}

@Composable
fun VettingListScreen(
    state: VettingListUiState,
    onSync: () -> Unit,
    onOpen: (String) -> Unit,
    now: () -> Long = System::currentTimeMillis
) = CBOCollectorTheme {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SaColors.Surface)
            .padding(20.dp, 20.dp, 20.dp, 0.dp)
            .testTag(ListTags.SCREEN)
    ) {
        ScreenHeader(
            title = stringResource(R.string.vetting_title),
            subtitle = subtitleFor(state, now),
            modifier = Modifier.padding(bottom = 12.dp),
            trailing = { SyncButton(state.refreshing, onSync) }
        )

        state.notice?.let { Notice(it, state.meta) }

        when {
            !state.loaded -> Unit
            state.items.isEmpty() -> EmptyState(state.refreshing, onSync)
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().testTag(ListTags.LIST),
                contentPadding = PaddingValues(top = 6.dp, bottom = 26.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(state.items, key = { it.id }) { RecordRow(it, onOpen) }
            }
        }
    }
}

@Composable
private fun subtitleFor(state: VettingListUiState, now: () -> Long): String? {
    if (!state.loaded || state.items.isEmpty()) return null
    val summary = stringResource(R.string.vetting_list_summary, state.items.size, state.decided)
    val meta = state.meta ?: return summary
    return summary + " · " + stringResource(R.string.vetting_updated, ageText(ageOf(meta.fetchedAtMillis, now())))
}

@Composable
internal fun ageText(age: Age): String = when (age) {
    Age.JustNow -> stringResource(R.string.vetting_age_now)
    is Age.Minutes -> stringResource(R.string.vetting_age_minutes, age.n)
    is Age.Hours -> stringResource(R.string.vetting_age_hours, age.n)
    is Age.Days -> stringResource(R.string.vetting_age_days, age.n)
}

@Composable
private fun SyncButton(refreshing: Boolean, onSync: () -> Unit) {
    FilledPillButton(
        onClick = onSync,
        enabled = !refreshing,
        contentPadding = PaddingValues(vertical = 9.dp, horizontal = 16.dp),
        modifier = Modifier.testTag(ListTags.SYNC)
    ) {
        Text(
            stringResource(if (refreshing) R.string.vetting_syncing else R.string.vetting_sync),
            fontFamily = Figtree, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = SaColors.Ink
        )
    }
}

@Composable
private fun Notice(notice: ListNotice, meta: RecordsMeta?) {
    val text = stringResource(
        when (notice) {
            ListNotice.OFFLINE -> R.string.vetting_notice_offline
            ListNotice.UNAVAILABLE -> R.string.vetting_notice_unavailable
            ListNotice.FAILED -> R.string.vetting_notice_failed
            ListNotice.STALE -> R.string.vetting_notice_stale
        }
    )
    Text(
        text,
        fontFamily = Figtree, fontSize = 12.5.sp, lineHeight = 18.sp, color = SaColors.TagWarnText,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(SaColors.TagWarnBg)
            .padding(14.dp)
            .testTag(ListTags.NOTICE)
    )
}

@Composable
private fun EmptyState(refreshing: Boolean, onSync: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 24.dp)
            .testTag(if (refreshing) ListTags.LOADING else ListTags.EMPTY)
            .semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (refreshing) {
            Text(stringResource(R.string.vetting_loading), fontFamily = Poppins, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = SaColors.Ink)
        } else {
            Text(stringResource(R.string.vetting_empty_title), fontFamily = Poppins, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = SaColors.Ink)
            Text(
                stringResource(R.string.vetting_empty_body),
                fontFamily = Figtree, fontSize = 14.5.sp, lineHeight = 23.sp, color = SaColors.Muted
            )
        }
    }
}

@Composable
private fun RecordRow(item: RecordItem, onOpen: (String) -> Unit) {
    val decisionText = stringResource(
        when (item.decision) {
            null -> R.string.vetting_decision_none
            DecisionOutcome.APPROVE -> R.string.vetting_outcome_approve
            DecisionOutcome.REJECT -> R.string.vetting_outcome_reject
            DecisionOutcome.FLAG -> R.string.vetting_outcome_flag
        }
    )
    CardButton(
        onClick = { onOpen(item.id) },
        modifier = Modifier
            .fillMaxWidth()
            .testTag(ListTags.item(item.id))
            .semantics(mergeDescendants = true) { contentDescription = "${item.legalName}, ${item.province}, $decisionText" },
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                item.legalName,
                fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 14.5.sp, color = SaColors.Ink,
                maxLines = 2, overflow = TextOverflow.Ellipsis
            )
            Text(
                listOf(item.province, item.contactName).filter { it.isNotBlank() }.joinToString(" · "),
                fontFamily = Figtree, fontSize = 12.sp, color = SaColors.MutedLight,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            // Only while the decision has not reached the server: once it has, there is nothing to say.
            val sync = item.decisionSync
            if (sync != null && sync != DecisionSyncDisplay.SYNCED) {
                Text(
                    stringResource(sync.hintRes()),
                    fontFamily = Figtree, fontSize = 11.5.sp, lineHeight = 16.sp,
                    color = if (sync.isFailed) SaColors.TagErrorText else SaColors.Muted,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
        DecisionBadge(item.decision)
    }
}
