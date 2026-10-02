package com.example.client.ui.vetting

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.client.R
import com.example.client.auth.SessionManager
import com.example.client.data.local.entity.DecisionOutcome
import com.example.client.data.local.entity.Tone
import com.example.client.data.repository.VettingRecordsRepository
import com.example.client.data.repository.VettingRepository
import com.example.client.sync.VettingSyncTrigger
import com.example.client.ui.cbo.formatWhen
import com.example.client.ui.components.ErrorTagPalette
import com.example.client.ui.components.SyncTabScreen
import com.example.client.ui.components.TagBadge
import com.example.client.ui.components.palette
import com.example.client.ui.theme.AlertCircleGlyph
import com.example.client.ui.theme.CBOCollectorTheme
import com.example.client.ui.theme.Figtree
import com.example.client.ui.theme.GlyphPaths
import com.example.client.ui.theme.SaColors
import com.example.client.ui.theme.StrokeIcon
import com.example.client.ui.util.rememberIsOnline
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

object VettingSyncTags {
    fun item(id: String) = "vetting_sync_item_$id"
}

/** One decision the officer has made, as the Sync tab lists it. */
data class DecisionQueueItem(
    val id: String,
    /** The record's name, or its id when the record is no longer in the cache. */
    val recordName: String,
    val outcome: DecisionOutcome,
    val decidedAt: Long,
    val sync: DecisionSyncDisplay
)

data class VettingSyncUiState(
    val items: List<DecisionQueueItem> = emptyList(),
    /** Decisions some other officer made that are waiting on this phone for that officer to sign in (#70). */
    val otherAccountsWaiting: Int = 0
) {
    /** Decisions not yet on the server: waiting to be sent, or failed. */
    val waiting: List<DecisionQueueItem> get() = items.filter { it.sync != DecisionSyncDisplay.SYNCED }
    val failed: Int get() = items.count { it.sync.isFailed }

    /** What a sync run would actually send now. */
    val sendable: Int get() = items.count { it.sync == DecisionSyncDisplay.PENDING || it.sync == DecisionSyncDisplay.FAILED_WILL_RETRY }
}

/** What the Vetting Sync tab shows and can do: the officer's own decisions, sending them now, and signing out. */
@HiltViewModel
class VettingSyncViewModel @Inject constructor(
    vetting: VettingRepository,
    records: VettingRecordsRepository,
    private val syncTrigger: VettingSyncTrigger,
    private val sessionManager: SessionManager
) : ViewModel() {

    // Only this officer's own decisions: another officer's on the same phone are theirs to send (#70).
    private val officer = sessionManager.username()

    val uiState = combine(vetting.observeDecisionsBy(officer), records.observeRecords(), vetting.observeWaitingForOthers(officer)) { decisions, cached, others ->
        val names = cached.associate { it.id to it.legalName }
        VettingSyncUiState(
            items = decisions.map {
                DecisionQueueItem(it.id, names[it.foodspaceRecordId] ?: it.foodspaceRecordId, it.outcome, it.decisionTimestamp, it.syncDisplay())
            },
            otherAccountsWaiting = others
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), VettingSyncUiState())

    /** Queues a sync now; it waits for a network if there is none. */
    fun syncNow() = syncTrigger.syncVettingDecisionsNow()

    fun signOut() = sessionManager.endSession()
}

@Composable
fun VettingSyncRoute(viewModel: VettingSyncViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()
    VettingSyncScreen(state, onSync = viewModel::syncNow, onSignOut = viewModel::signOut)
}

/** The demo's Sync tab for a vetting officer: which decisions are not on the server yet, send them now, sign out. */
@Composable
fun VettingSyncScreen(
    state: VettingSyncUiState,
    onSync: () -> Unit,
    onSignOut: () -> Unit
) = CBOCollectorTheme {
    val online = rememberIsOnline()
    val waiting = state.waiting
    SyncTabScreen(
        subtitle = if (waiting.isEmpty()) stringResource(R.string.sync_all_clear) else stringResource(R.string.sync_waiting, waiting.size),
        online = online,
        failedMessage = if (state.failed > 0) stringResource(R.string.vetting_sync_panel_failed, state.failed) else null,
        syncLabel = when {
            state.sendable == 0 -> stringResource(R.string.sync_all_synced)
            online -> stringResource(R.string.sync_now, state.sendable)
            else -> stringResource(R.string.sync_retry_online)
        },
        syncEnabled = online && state.sendable > 0,
        onSync = onSync,
        onSignOut = onSignOut,
        unsentCount = waiting.size,
        otherAccountsWaiting = state.otherAccountsWaiting
    ) {
        waiting.forEach { DecisionQueueRow(it) }
    }
}

@Composable
private fun DecisionQueueRow(item: DecisionQueueItem) {
    val failed = item.sync.isFailed
    val shape = RoundedCornerShape(12.dp)
    val outcome = stringResource(item.outcome.labelRes())
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(SaColors.White)
            .border(if (failed) 1.5.dp else 1.dp, if (failed) SaColors.Error else SaColors.inkAlpha(0.12f), shape)
            .padding(16.dp, 14.dp)
            .testTag(VettingSyncTags.item(item.id))
            .semantics(mergeDescendants = true) { contentDescription = "${item.recordName}, $outcome" },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(if (failed) SaColors.TagErrorBg else SaColors.YellowTickBg),
            contentAlignment = Alignment.Center
        ) {
            if (failed) AlertCircleGlyph(tint = SaColors.TagErrorText, modifier = Modifier.size(18.dp))
            else StrokeIcon(pathData = GlyphPaths.NavHistory, tint = SaColors.LinkGold, modifier = Modifier.size(16.dp))
        }
        Column(modifier = Modifier
            .weight(1f)
            .padding(horizontal = 12.dp)) {
            Text(item.recordName, fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = SaColors.Ink)
            Text(
                outcome + " · " + formatWhen(item.decidedAt),
                fontFamily = Figtree, fontSize = 11.5.sp, color = SaColors.MutedLight
            )
            Text(
                stringResource(item.sync.hintRes()),
                fontFamily = Figtree, fontSize = 11.5.sp, lineHeight = 16.sp,
                color = if (failed) SaColors.TagErrorText else SaColors.Muted,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        if (failed) TagBadge(stringResource(R.string.submissions_status_failed), ErrorTagPalette)
        else TagBadge(stringResource(R.string.submissions_status_pending), Tone.WARN.palette())
    }
}

private fun DecisionOutcome.labelRes() = when (this) {
    DecisionOutcome.APPROVE -> R.string.vetting_outcome_approve
    DecisionOutcome.REJECT -> R.string.vetting_outcome_reject
    DecisionOutcome.FLAG -> R.string.vetting_outcome_flag
}
