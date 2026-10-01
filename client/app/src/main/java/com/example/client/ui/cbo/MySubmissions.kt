package com.example.client.ui.cbo

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import com.example.client.data.local.entity.Tone
import com.example.client.ui.components.ErrorTagPalette
import com.example.client.ui.components.ScreenHeader
import com.example.client.ui.components.TagBadge
import com.example.client.ui.components.TagPalette
import com.example.client.ui.components.palette
import com.example.client.ui.theme.AlertCircleGlyph
import com.example.client.ui.theme.CBOCollectorTheme
import com.example.client.ui.theme.Figtree
import com.example.client.ui.theme.GlyphPaths
import com.example.client.ui.theme.SaColors
import com.example.client.ui.theme.StrokeIcon
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

// Look follows the CBO Collector UI demo's Sync and History screens: a status panel, white rows with a round
// icon, and TagBadge pills. The demo only has OK / WARN / NEW tags, so Pending reuses WARN (as its "Queued"),
// Synced uses OK, and Failed gets a red tag, a red icon and a red outline so it cannot be mistaken for Pending.

private data class StatusStyle(val label: Int, val hint: Int, val palette: TagPalette, val circleBg: Color, val circleFg: Color)

private fun SubmissionDisplay.style() = when (this) {
    SubmissionDisplay.PENDING -> StatusStyle(
        R.string.submissions_status_pending, R.string.submissions_hint_pending,
        Tone.WARN.palette(), SaColors.YellowTickBg, SaColors.LinkGold
    )
    SubmissionDisplay.SYNCED -> StatusStyle(
        R.string.submissions_status_synced, R.string.submissions_hint_synced,
        Tone.OK.palette(), SaColors.SurfaceAlt, SaColors.Ink
    )
    SubmissionDisplay.FAILED_WILL_RETRY -> StatusStyle(
        R.string.submissions_status_failed, R.string.submissions_hint_failed_retry,
        ErrorTagPalette, SaColors.TagErrorBg, SaColors.TagErrorText
    )
    SubmissionDisplay.FAILED_FINAL -> StatusStyle(
        R.string.submissions_status_failed, R.string.submissions_hint_failed_final,
        ErrorTagPalette, SaColors.TagErrorBg, SaColors.TagErrorText
    )
}

// ── Badge on the form screen ───────────────────────────────────────────────────

@Composable
fun SyncStatusBadgeRoute(onClick: () -> Unit, viewModel: SubmissionsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()
    SyncStatusBadge(state, onClick)
}

/** A strip under the header: a pill per status with its count; tapping it opens the list. */
@Composable
fun SyncStatusBadge(state: SubmissionsUiState, onClick: () -> Unit) = CBOCollectorTheme {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SaColors.SurfaceAlt)
            .clickable(onClick = onClick)
            .defaultMinSize(minHeight = 48.dp)
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .testTag(SubmissionsTags.BADGE),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (state.items.isEmpty()) {
                Text(stringResource(R.string.submissions_badge_none), fontFamily = Figtree, fontSize = 12.5.sp, color = SaColors.MutedLight)
            } else {
                if (state.failed > 0) TagBadge(stringResource(R.string.submissions_badge_failed, state.failed), ErrorTagPalette)
                if (state.pending > 0) TagBadge(stringResource(R.string.submissions_badge_pending, state.pending), Tone.WARN)
                if (state.synced > 0) TagBadge(stringResource(R.string.submissions_badge_synced, state.synced), Tone.OK)
            }
        }
        Text(
            stringResource(R.string.submissions_view_all),
            fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = SaColors.LinkGold
        )
    }
}

// ── My submissions list ────────────────────────────────────────────────────────

@Composable
fun MySubmissionsRoute(onBack: () -> Unit = {}, viewModel: SubmissionsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()
    MySubmissionsScreen(state, onBack)
}

@Composable
fun MySubmissionsScreen(state: SubmissionsUiState, onBack: () -> Unit) = CBOCollectorTheme {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SaColors.Surface)
            .padding(20.dp, 20.dp, 20.dp, 0.dp)
    ) {
        ScreenHeader(
            title = stringResource(R.string.submissions_title),
            onBack = onBack,
            subtitle = stringResource(R.string.submissions_summary_safe, state.items.size),
            modifier = Modifier.padding(bottom = 16.dp)
        )

        if (state.items.isEmpty()) {
            Text(
                stringResource(R.string.submissions_empty),
                fontFamily = Figtree, fontSize = 14.5.sp, lineHeight = 23.sp, color = SaColors.Muted,
                modifier = Modifier.testTag(SubmissionsTags.EMPTY)
            )
        } else {
            StatusPanel(state)

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag(SubmissionsTags.LIST),
                contentPadding = PaddingValues(top = 18.dp, bottom = 26.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(state.items, key = { it.id }) { SubmissionRow(it) }
            }
        }
    }
}

/** The demo Sync screen's status panel: failures first, then what is waiting, then "all clear". */
@Composable
private fun StatusPanel(state: SubmissionsUiState) {
    val (bg, text, message) = when {
        state.failed > 0 -> Triple(SaColors.TagErrorBg, SaColors.TagErrorText, stringResource(R.string.submissions_panel_failed, state.failed))
        state.pending > 0 -> Triple(SaColors.SurfaceAlt, SaColors.Ink, stringResource(R.string.submissions_panel_pending, state.pending))
        else -> Triple(SaColors.SurfaceAlt, SaColors.Ink, stringResource(R.string.submissions_panel_synced))
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .padding(18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(if (state.failed > 0) SaColors.Error else SaColors.YellowDark)
        )
        Text(
            message,
            fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp, lineHeight = 19.sp, color = text,
            modifier = Modifier.padding(start = 10.dp)
        )
    }
}

@Composable
private fun SubmissionRow(item: SubmissionItem) {
    val style = item.display.style()
    val label = stringResource(style.label)
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(SaColors.White)
            .border(if (item.display.isFailed) 1.5.dp else 1.dp, if (item.display.isFailed) SaColors.Error else SaColors.inkAlpha(0.12f), shape)
            .padding(16.dp, 14.dp)
            .testTag(SubmissionsTags.item(item.id))
            .semantics(mergeDescendants = true) { contentDescription = "${item.donorName}, $label" },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(style.circleBg),
            contentAlignment = Alignment.Center
        ) {
            when (item.display) {
                SubmissionDisplay.SYNCED ->
                    StrokeIcon(pathData = GlyphPaths.Check, tint = style.circleFg, modifier = Modifier.size(16.dp))
                SubmissionDisplay.PENDING ->
                    StrokeIcon(pathData = GlyphPaths.NavHistory, tint = style.circleFg, modifier = Modifier.size(16.dp))
                else -> AlertCircleGlyph(tint = style.circleFg, modifier = Modifier.size(18.dp))
            }
        }
        Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(item.donorName, fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = SaColors.Ink)
            Text(formatWhen(item.createdAt), fontFamily = Figtree, fontSize = 11.5.sp, color = SaColors.MutedLight)
            Text(
                stringResource(style.hint),
                fontFamily = Figtree, fontSize = 11.5.sp, lineHeight = 16.sp,
                color = if (item.display.isFailed) SaColors.TagErrorText else SaColors.Muted,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        TagBadge(label, style.palette)
    }
}

private fun formatWhen(epochMillis: Long): String =
    SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(epochMillis))
