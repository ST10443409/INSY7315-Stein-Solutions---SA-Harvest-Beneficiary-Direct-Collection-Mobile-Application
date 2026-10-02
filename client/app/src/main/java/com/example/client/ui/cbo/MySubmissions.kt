package com.example.client.ui.cbo

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.example.client.auth.SessionManager
import com.example.client.data.local.entity.AttachmentKind
import com.example.client.data.local.entity.CboCollectionEntity
import com.example.client.data.local.entity.CollectionAttachmentEntity
import com.example.client.data.local.entity.SyncStatus
import com.example.client.data.local.entity.Tone
import com.example.client.data.repository.CboCollectionRepository
import com.example.client.network.CboSyncErrorCodes
import com.example.client.sync.CboSyncProcessor
import com.example.client.sync.SyncPolicy
import com.example.client.ui.components.ErrorTagPalette
import com.example.client.ui.components.TagBadge
import com.example.client.ui.components.TagPalette
import com.example.client.ui.components.palette
import com.example.client.ui.theme.AlertCircleGlyph
import com.example.client.ui.theme.CBOCollectorTheme
import com.example.client.ui.theme.Figtree
import com.example.client.ui.theme.GlyphPaths
import com.example.client.ui.theme.Poppins
import com.example.client.ui.theme.SaColors
import com.example.client.ui.theme.StrokeIcon
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

object SubmissionsTags {
    const val LIST = "submissions_list"
    const val EMPTY = "submissions_empty"
    fun item(id: String) = "submission_$id"
}

/**
 * How a submission is shown. FAILED is split so the collector knows whether the app is still trying, and, when it
 * has stopped, why: the server already has this collection (DUPLICATE), the server refused the data (REJECTED),
 * or the retries ran out (FINAL).
 */
enum class SubmissionDisplay { PENDING, SYNCED, FAILED_WILL_RETRY, FAILED_FINAL, FAILED_DUPLICATE, FAILED_REJECTED }

data class SubmissionItem(
    val id: String,
    val donorName: String,
    val createdAt: Long,
    val display: SubmissionDisplay,
    /** How many of the two signatures (donor, CBO) were captured for this collection. */
    val signatureCount: Int = 0,
    /** How many donation photos were captured for this collection. */
    val photoCount: Int = 0
)

data class SubmissionsUiState(
    val items: List<SubmissionItem> = emptyList(),
    /**
     * Signatures and photos of records the server already has that are still to be uploaded (they upload separately from
     * the record, so a record can be "synced" while its pictures are not). Zero for files the server refused for good.
     */
    val attachmentsWaiting: Int = 0,
    /** Records some other account captured that are waiting on this phone for that person to sign in (#70). */
    val otherAccountsWaiting: Int = 0
) {
    /**
     * What would be left on this phone, unsent, if the user signed out now: their records not yet on the server, and the
     * pictures of records that are. It all stays safe, and is sent when they sign in again (#70).
     */
    val unsent: Int get() = waiting.size + attachmentsWaiting

    val pending: Int get() = items.count { it.display == SubmissionDisplay.PENDING }
    val synced: Int get() = items.count { it.display == SubmissionDisplay.SYNCED }
    val failed: Int get() = items.count { it.display.isFailed }

    /** Everything not yet on the server: waiting to be sent, or failed. */
    val waiting: List<SubmissionItem> get() = items.filter { it.display != SubmissionDisplay.SYNCED }

    /** What a sync run would actually send now: waiting records plus failed ones that still have retries left. */
    val sendable: Int get() = items.count { it.display == SubmissionDisplay.PENDING || it.display == SubmissionDisplay.FAILED_WILL_RETRY }
}

val SubmissionDisplay.isFailed: Boolean
    get() = this != SubmissionDisplay.PENDING && this != SubmissionDisplay.SYNCED

fun CboCollectionEntity.toSubmissionItem(attachments: List<CollectionAttachmentEntity> = emptyList()) = SubmissionItem(
    id = id,
    donorName = donorName,
    createdAt = createdAt,
    display = when (syncStatus) {
        SyncStatus.PENDING -> SubmissionDisplay.PENDING
        SyncStatus.SYNCED -> SubmissionDisplay.SYNCED
        SyncStatus.FAILED -> when {
            syncErrorCode == CboSyncErrorCodes.DUPLICATE_DETECTED -> SubmissionDisplay.FAILED_DUPLICATE
            retryCount < CboSyncProcessor.MAX_RETRIES -> SubmissionDisplay.FAILED_WILL_RETRY
            syncErrorCode == CboSyncErrorCodes.VALIDATION_FAILED -> SubmissionDisplay.FAILED_REJECTED
            else -> SubmissionDisplay.FAILED_FINAL
        }
    },
    signatureCount = attachments.count {
        it.kind == AttachmentKind.DONOR_SIGNATURE || it.kind == AttachmentKind.CBO_SIGNATURE
    },
    photoCount = attachments.count { it.kind == AttachmentKind.PHOTO }
)

/**
 * What [author] captured on this device, with its signature and photo counts, as the screens show them. Updates live.
 * Only their own work: on a phone other people sign in to, someone else's records are not theirs to see or send (#70),
 * though how many are waiting for their author is counted.
 */
fun CboCollectionRepository.observeSubmissions(author: String?): Flow<SubmissionsUiState> =
    combine(observeByAuthor(author), observeAttachments(), observeWaitingForOthers(author)) { records, attachments, others ->
        val byCollection = attachments.groupBy { it.collectionId }
        val serverHas = records.filter { it.serverHasIt() }.map { it.id }.toSet()
        SubmissionsUiState(
            items = records.map { it.toSubmissionItem(byCollection[it.id].orEmpty()) },
            attachmentsWaiting = attachments.count { it.collectionId in serverHas && it.isDueForUpload() },
            otherAccountsWaiting = others
        )
    }

/** The server holds this record: delivered, or kept there as a suspected duplicate for an Admin to review. Pictures upload for these. */
internal fun CboCollectionEntity.serverHasIt() =
    syncStatus == SyncStatus.SYNCED || (syncStatus == SyncStatus.FAILED && syncErrorCode == CboSyncErrorCodes.DUPLICATE_DETECTED)

/** Still to upload: not on the server yet, and not one the server refused for good. */
internal fun CollectionAttachmentEntity.isDueForUpload() =
    syncStatus == SyncStatus.PENDING || (syncStatus == SyncStatus.FAILED && retryCount < SyncPolicy.MAX_RETRIES)

/**
 * A thin UI layer over [CboCollectionRepository]: the same flows the rest of the app uses, no second query. Room
 * re-emits whenever the sync worker changes a record, so the UI updates live.
 *
 * Scope: the records stored on this device. The session carries no collector id yet, so this cannot filter
 * further; nothing here needs (or checks for) Admin permissions.
 */
@HiltViewModel
class SubmissionsViewModel @Inject constructor(
    repository: CboCollectionRepository,
    sessionManager: SessionManager
) : ViewModel() {
    val uiState = repository.observeSubmissions(sessionManager.username())
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SubmissionsUiState())
}

// Look follows the CBO Collector UI demo's Sync and History screens: white rows with a round icon, and TagBadge pills.
// The demo only has OK / WARN / NEW tags, so Pending reuses WARN (as its "Queued"), Synced uses OK, and Failed gets a
// red tag, a red icon and a red outline so it cannot be mistaken for Pending.
internal data class StatusStyle(val label: Int, val hint: Int, val palette: TagPalette, val circleBg: Color, val circleFg: Color)

internal fun SubmissionDisplay.style() = when (this) {
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
    SubmissionDisplay.FAILED_DUPLICATE -> StatusStyle(
        R.string.submissions_status_duplicate, R.string.submissions_hint_duplicate,
        ErrorTagPalette, SaColors.TagErrorBg, SaColors.TagErrorText
    )
    SubmissionDisplay.FAILED_REJECTED -> StatusStyle(
        R.string.submissions_status_rejected, R.string.submissions_hint_rejected,
        ErrorTagPalette, SaColors.TagErrorBg, SaColors.TagErrorText
    )
}

// ── History tab ──────────────────────────────────────────────────────────

@Composable
fun HistoryRoute(viewModel: SubmissionsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()
    HistoryScreen(state)
}

/** The demo's History tab: every collection saved on this device, newest first, each with where it has got to. */
@Composable
fun HistoryScreen(state: SubmissionsUiState) = CBOCollectorTheme {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SaColors.Surface)
            .padding(20.dp, 20.dp, 20.dp, 0.dp)
    ) {
        Text(
            stringResource(R.string.history_title),
            fontFamily = Poppins, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, color = SaColors.Ink
        )
        Text(
            stringResource(R.string.history_subtitle, state.items.size),
            fontFamily = Figtree, fontSize = 12.5.sp, color = SaColors.MutedLight,
            modifier = Modifier.padding(top = 4.dp, bottom = 18.dp)
        )
        if (state.items.isEmpty()) {
            Text(
                stringResource(R.string.submissions_empty),
                fontFamily = Figtree, fontSize = 14.5.sp, lineHeight = 23.sp, color = SaColors.Muted,
                modifier = Modifier.testTag(SubmissionsTags.EMPTY)
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag(SubmissionsTags.LIST),
                contentPadding = PaddingValues(bottom = 26.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(state.items, key = { it.id }) { SubmissionRow(it) }
            }
        }
    }
}

@Composable
internal fun SubmissionRow(item: SubmissionItem) {
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
        Column(modifier = Modifier
            .weight(1f)
            .padding(horizontal = 12.dp)) {
            Text(item.donorName, fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = SaColors.Ink)
            Text(formatWhen(item.createdAt), fontFamily = Figtree, fontSize = 11.5.sp, color = SaColors.MutedLight)
            Text(
                stringResource(R.string.sync_row_attachments, item.signatureCount, item.photoCount),
                fontFamily = Figtree, fontSize = 11.5.sp, color = SaColors.MutedLight
            )
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

internal fun formatWhen(epochMillis: Long): String =
    SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(epochMillis))
