package com.example.client.ui.cbo

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.client.R
import com.example.client.data.local.entity.CboCollectionEntity
import com.example.client.data.local.entity.SyncStatus
import com.example.client.data.repository.CboCollectionRepository
import com.example.client.sync.CboSyncProcessor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

object SubmissionsTags {
    const val BADGE = "submissions_badge"
    const val LIST = "submissions_list"
    const val EMPTY = "submissions_empty"
    const val BACK = "submissions_back"
    fun item(id: String) = "submission_$id"
}

/** How a submission is shown. FAILED is split so the collector knows whether the app is still trying. */
enum class SubmissionDisplay { PENDING, SYNCED, FAILED_WILL_RETRY, FAILED_FINAL }

data class SubmissionItem(
    val id: String,
    val donorName: String,
    val createdAt: Long,
    val display: SubmissionDisplay
)

data class SubmissionsUiState(
    val items: List<SubmissionItem> = emptyList()
) {
    val pending: Int get() = items.count { it.display == SubmissionDisplay.PENDING }
    val synced: Int get() = items.count { it.display == SubmissionDisplay.SYNCED }
    val failed: Int get() = items.count { it.display.isFailed }
}

val SubmissionDisplay.isFailed: Boolean
    get() = this == SubmissionDisplay.FAILED_WILL_RETRY || this == SubmissionDisplay.FAILED_FINAL

fun CboCollectionEntity.toSubmissionItem() = SubmissionItem(
    id = id,
    donorName = donorName,
    createdAt = createdAt,
    display = when (syncStatus) {
        SyncStatus.PENDING -> SubmissionDisplay.PENDING
        SyncStatus.SYNCED -> SubmissionDisplay.SYNCED
        SyncStatus.FAILED ->
            if (retryCount < CboSyncProcessor.MAX_RETRIES) SubmissionDisplay.FAILED_WILL_RETRY
            else SubmissionDisplay.FAILED_FINAL
    }
)

/**
 * A thin UI layer over [CboCollectionRepository.observeAll]: the same flow the rest of the app uses, no second
 * query. Room re-emits whenever the sync worker changes a record, so the UI updates live.
 *
 * Scope: the records stored on this device. The session carries no collector id yet, so this cannot filter
 * further; nothing here needs (or checks for) Admin permissions.
 */
@HiltViewModel
class SubmissionsViewModel @Inject constructor(
    repository: CboCollectionRepository
) : ViewModel() {

    val uiState: StateFlow<SubmissionsUiState> = repository.observeAll()
        .map { records -> SubmissionsUiState(records.map { it.toSubmissionItem() }) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SubmissionsUiState())
}

// Status colours: Pending is amber, Synced green, Failed red. Failed also has its own icon, so the three
// are told apart by more than colour (and more than the label).
private val PendingBg = Color(0xFFFFF3C2)
private val PendingFg = Color(0xFF7A6100)
private val SyncedBg = Color(0xFFE9F0E9)
private val SyncedFg = Color(0xFF2F5D3A)
private val FailedBg = Color(0xFFFDE7E4)
private val FailedFg = Color(0xFFB3261B)

private data class StatusStyle(val label: Int, val hint: Int, val icon: ImageVector, val bg: Color, val fg: Color)

private fun SubmissionDisplay.style() = when (this) {
    SubmissionDisplay.PENDING -> StatusStyle(
        R.string.submissions_status_pending, R.string.submissions_hint_pending, Icons.Filled.Refresh, PendingBg, PendingFg
    )
    SubmissionDisplay.SYNCED -> StatusStyle(
        R.string.submissions_status_synced, R.string.submissions_hint_synced, Icons.Filled.CheckCircle, SyncedBg, SyncedFg
    )
    SubmissionDisplay.FAILED_WILL_RETRY -> StatusStyle(
        R.string.submissions_status_failed, R.string.submissions_hint_failed_retry, Icons.Filled.Warning, FailedBg, FailedFg
    )
    SubmissionDisplay.FAILED_FINAL -> StatusStyle(
        R.string.submissions_status_failed, R.string.submissions_hint_failed_final, Icons.Filled.Warning, FailedBg, FailedFg
    )
}

// ── Badge on the form screen ───────────────────────────────────────────────────

@Composable
fun SyncStatusBadgeRoute(onClick: () -> Unit, viewModel: SubmissionsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()
    SyncStatusBadge(state, onClick)
}

/** One line under the app bar: counts per status; tapping it opens the list. */
@Composable
fun SyncStatusBadge(state: SubmissionsUiState, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag(SubmissionsTags.BADGE),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (state.items.isEmpty()) {
            Text(stringResource(R.string.submissions_badge_none), fontSize = 14.sp, modifier = Modifier.weight(1f))
        } else {
            Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.failed > 0) CountPill(SubmissionDisplay.FAILED_WILL_RETRY, stringResource(R.string.submissions_badge_failed, state.failed))
                if (state.pending > 0) CountPill(SubmissionDisplay.PENDING, stringResource(R.string.submissions_badge_pending, state.pending))
                if (state.synced > 0) CountPill(SubmissionDisplay.SYNCED, stringResource(R.string.submissions_badge_synced, state.synced))
            }
        }
        Text(stringResource(R.string.submissions_view_all), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun CountPill(display: SubmissionDisplay, text: String) {
    val style = display.style()
    Row(
        modifier = Modifier
            .background(style.bg, RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(style.icon, contentDescription = null, tint = style.fg, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(text, color = style.fg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ── My submissions list ────────────────────────────────────────────────────────

@Composable
fun MySubmissionsRoute(onBack: () -> Unit = {}, viewModel: SubmissionsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()
    MySubmissionsScreen(state, onBack)
}

@Composable
fun MySubmissionsScreen(state: SubmissionsUiState, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            TextButton(onClick = onBack, modifier = Modifier.testTag(SubmissionsTags.BACK)) {
                Text(stringResource(R.string.submissions_back), fontSize = 16.sp)
            }
            Text(stringResource(R.string.submissions_title), style = MaterialTheme.typography.titleLarge)
        }

        if (state.items.isEmpty()) {
            Text(
                stringResource(R.string.submissions_empty),
                modifier = Modifier
                    .padding(24.dp)
                    .testTag(SubmissionsTags.EMPTY)
            )
            return@Column
        }

        Text(
            stringResource(R.string.submissions_summary_safe, state.items.size),
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .testTag(SubmissionsTags.LIST),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(state.items, key = { it.id }) { SubmissionRow(it) }
        }
    }
}

@Composable
private fun SubmissionRow(item: SubmissionItem) {
    val style = item.display.style()
    val label = stringResource(style.label)
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(if (item.display.isFailed) 2.dp else 1.dp, if (item.display.isFailed) style.fg else MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(SubmissionsTags.item(item.id))
            .semantics(mergeDescendants = true) { contentDescription = "${item.donorName}, $label" }
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(item.donorName, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    Text(formatWhen(item.createdAt), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(
                    modifier = Modifier
                        .background(style.bg, RoundedCornerShape(50))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(style.icon, contentDescription = null, tint = style.fg, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(label, color = style.fg, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                }
            }
            Text(stringResource(style.hint), fontSize = 13.sp, color = if (item.display.isFailed) style.fg else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun formatWhen(epochMillis: Long): String =
    SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(epochMillis))
