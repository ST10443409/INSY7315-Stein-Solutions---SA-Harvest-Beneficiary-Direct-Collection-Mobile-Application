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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.client.R
import com.example.client.data.local.entity.Tone
import com.example.client.data.repository.AdminSyncStatusRepository
import com.example.client.data.repository.FormSyncCounts
import com.example.client.data.repository.SyncStatusResult
import com.example.client.data.repository.SyncStatusSnapshot
import com.example.client.ui.components.ErrorTagPalette
import com.example.client.ui.components.FilledPillButton
import com.example.client.ui.components.ScreenHeader
import com.example.client.ui.components.TagBadge
import com.example.client.ui.theme.CBOCollectorTheme
import com.example.client.ui.theme.Figtree
import com.example.client.ui.theme.Poppins
import com.example.client.ui.theme.SaColors
import com.example.client.ui.vetting.ageOf
import com.example.client.ui.vetting.ageText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

object SyncMonitorTags {
    const val SCREEN = "sync_monitor"
    const val REFRESH = "sync_monitor_refresh"
    const val NOTICE = "sync_monitor_notice"
    const val LOADING = "sync_monitor_loading"
    const val FORM1 = "sync_monitor_form1"
    const val FORM2 = "sync_monitor_form2"

    /** One row of a form's card, e.g. `sync_monitor_form1_needs_attention`. */
    fun row(card: String, row: SyncRow) = "${card}_${row.name.lowercase()}"
}

/** The states a form's records can be in on the server, in the order a card lists them. */
enum class SyncRow { WAITING, RETRYING, NEEDS_ATTENTION, FORWARDED, DUPLICATES, SUPERSEDED }

fun FormSyncCounts.count(row: SyncRow): Int = when (row) {
    SyncRow.WAITING -> waiting
    SyncRow.RETRYING -> retrying
    SyncRow.NEEDS_ATTENTION -> needsAttention
    SyncRow.FORWARDED -> forwarded
    SyncRow.DUPLICATES -> duplicates
    SyncRow.SUPERSEDED -> superseded
}

/** Something worth telling the Admin after a refresh; the counts already on screen stay. */
enum class MonitorNotice { OFFLINE, DENIED, FAILED }

data class SyncMonitorUiState(
    val loading: Boolean = true,
    /** The last counts the server gave, kept while a refresh runs or fails. Null until the first answer. */
    val snapshot: SyncStatusSnapshot? = null,
    val notice: MonitorNotice? = null
)

/** Loads the server's counts when the screen opens and whenever the Admin taps Refresh. */
@HiltViewModel
class SyncMonitorViewModel @Inject constructor(
    private val repository: AdminSyncStatusRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SyncMonitorUiState())
    val uiState: StateFlow<SyncMonitorUiState> = _uiState.asStateFlow()

    private var running = false

    init {
        refresh()
    }

    fun refresh() {
        if (running) return // one request at a time
        running = true
        _uiState.value = _uiState.value.copy(loading = true)
        viewModelScope.launch {
            val result = repository.load()
            _uiState.value = when (result) {
                is SyncStatusResult.Loaded -> SyncMonitorUiState(loading = false, snapshot = result.snapshot, notice = null)
                SyncStatusResult.Offline -> _uiState.value.copy(loading = false, notice = MonitorNotice.OFFLINE)
                SyncStatusResult.Denied -> _uiState.value.copy(loading = false, notice = MonitorNotice.DENIED)
                SyncStatusResult.Failed -> _uiState.value.copy(loading = false, notice = MonitorNotice.FAILED)
            }
            running = false
        }
    }
}

// ── Screen ─────────────────────────────────────────────────────────────────────

@Composable
fun SyncMonitorRoute(onBack: () -> Unit, viewModel: SyncMonitorViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()
    SyncMonitorScreen(state, onRefresh = viewModel::refresh, onBack = onBack)
}

@Composable
fun SyncMonitorScreen(
    state: SyncMonitorUiState,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
    now: () -> Long = System::currentTimeMillis
) = CBOCollectorTheme {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SaColors.Surface)
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
            .testTag(SyncMonitorTags.SCREEN),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        ScreenHeader(
            title = stringResource(AdminDestination.SYNC_MONITOR.title),
            subtitle = state.snapshot?.let { updatedText(it, now) },
            onBack = onBack,
            trailing = { RefreshButton(state.loading, onRefresh) }
        )

        state.notice?.let { Notice(it, hasCounts = state.snapshot != null) }

        val snapshot = state.snapshot
        if (snapshot == null) {
            if (state.loading) {
                Text(
                    stringResource(R.string.admin_sync_loading),
                    fontFamily = Figtree, fontSize = 14.sp, color = SaColors.Muted,
                    modifier = Modifier.testTag(SyncMonitorTags.LOADING)
                )
            }
        } else {
            FormCard(
                SyncMonitorTags.FORM1, stringResource(R.string.admin_sync_form1), snapshot.cboCollections,
                rows = listOf(SyncRow.WAITING, SyncRow.RETRYING, SyncRow.NEEDS_ATTENTION, SyncRow.FORWARDED, SyncRow.DUPLICATES)
            )
            FormCard(
                SyncMonitorTags.FORM2, stringResource(R.string.admin_sync_form2), snapshot.vettingDecisions,
                rows = listOf(SyncRow.WAITING, SyncRow.RETRYING, SyncRow.NEEDS_ATTENTION, SyncRow.FORWARDED, SyncRow.SUPERSEDED)
            )
        }
    }
}

@Composable
private fun updatedText(snapshot: SyncStatusSnapshot, now: () -> Long): String =
    stringResource(R.string.vetting_updated, ageText(ageOf(snapshot.takenAtMillis, now())))

@Composable
private fun RefreshButton(loading: Boolean, onRefresh: () -> Unit) {
    FilledPillButton(
        onClick = onRefresh,
        enabled = !loading,
        contentPadding = PaddingValues(vertical = 9.dp, horizontal = 16.dp),
        modifier = Modifier.testTag(SyncMonitorTags.REFRESH)
    ) {
        Text(
            stringResource(if (loading) R.string.admin_sync_refreshing else R.string.admin_sync_refresh),
            fontFamily = Figtree, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = SaColors.Ink
        )
    }
}

@Composable
private fun Notice(notice: MonitorNotice, hasCounts: Boolean) {
    val message = stringResource(
        when (notice) {
            MonitorNotice.OFFLINE -> if (hasCounts) R.string.admin_sync_notice_offline_kept else R.string.admin_sync_notice_offline
            MonitorNotice.DENIED -> R.string.admin_sync_notice_denied
            MonitorNotice.FAILED -> if (hasCounts) R.string.admin_sync_notice_failed_kept else R.string.admin_sync_notice_failed
        }
    )
    Text(
        message,
        fontFamily = Figtree, fontSize = 12.5.sp, lineHeight = 18.sp, color = SaColors.TagWarnText,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SaColors.TagWarnBg)
            .padding(14.dp)
            .testTag(SyncMonitorTags.NOTICE)
    )
}

@Composable
private fun FormCard(tag: String, title: String, counts: FormSyncCounts, rows: List<SyncRow>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SaColors.White)
            .padding(18.dp)
            .testTag(tag),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text(title, fontFamily = Poppins, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = SaColors.Ink)
            Text(
                stringResource(R.string.admin_sync_total, counts.total),
                fontFamily = Figtree, fontSize = 13.sp, color = SaColors.Muted
            )
        }
        rows.forEach { StateRow(SyncMonitorTags.row(tag, it), it, counts.count(it)) }
    }
}

@Composable
private fun StateRow(tag: String, row: SyncRow, count: Int) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag)
            .semantics(mergeDescendants = true) {}
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(stringResource(row.labelRes()), fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = SaColors.Ink)
            Text(stringResource(row.hintRes()), fontFamily = Figtree, fontSize = 12.sp, lineHeight = 17.sp, color = SaColors.MutedLight)
        }
        // Needs attention is the only row that asks something of the Admin, so it alone turns red, and only when it is not zero.
        when {
            row == SyncRow.NEEDS_ATTENTION && count > 0 -> TagBadge(count.toString(), ErrorTagPalette)
            row == SyncRow.FORWARDED -> TagBadge(count.toString(), Tone.OK)
            else -> TagBadge(count.toString(), Tone.NEW)
        }
    }
}

private fun SyncRow.labelRes() = when (this) {
    SyncRow.WAITING -> R.string.admin_sync_waiting
    SyncRow.RETRYING -> R.string.admin_sync_retrying
    SyncRow.NEEDS_ATTENTION -> R.string.admin_sync_needs_attention
    SyncRow.FORWARDED -> R.string.admin_sync_forwarded
    SyncRow.DUPLICATES -> R.string.admin_sync_duplicates
    SyncRow.SUPERSEDED -> R.string.admin_sync_superseded
}

private fun SyncRow.hintRes() = when (this) {
    SyncRow.WAITING -> R.string.admin_sync_waiting_hint
    SyncRow.RETRYING -> R.string.admin_sync_retrying_hint
    SyncRow.NEEDS_ATTENTION -> R.string.admin_sync_needs_attention_hint
    SyncRow.FORWARDED -> R.string.admin_sync_forwarded_hint
    SyncRow.DUPLICATES -> R.string.admin_sync_duplicates_hint
    SyncRow.SUPERSEDED -> R.string.admin_sync_superseded_hint
}
