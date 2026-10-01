package com.example.client.ui.admin

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.client.R
import com.example.client.data.repository.AdminResult
import com.example.client.data.repository.AdminSyncResolutionRepository
import com.example.client.data.repository.AttentionItem
import com.example.client.data.repository.SyncForm
import com.example.client.ui.components.CardButton
import com.example.client.ui.components.FilledPillButton
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
import kotlinx.coroutines.launch
import javax.inject.Inject

object FailedSyncTags {
    const val LIST_SCREEN = "failed_sync_list"
    const val REFRESH = "failed_sync_refresh"
    const val NOTICE = "failed_sync_notice"
    const val LOADING = "failed_sync_loading"
    const val EMPTY = "failed_sync_empty"
    const val LIST = "failed_sync_items"
    fun item(id: String) = "failed_sync_item_$id"
}

data class FailedSyncListUiState(
    val loading: Boolean = true,
    /** False until the first answer, so an empty list is not announced before it is known to be empty. */
    val loaded: Boolean = false,
    /** The last list the server gave, kept while a refresh runs or fails. */
    val items: List<AttentionItem> = emptyList(),
    val notice: AdminNotice? = null
)

/**
 * The records that need an Admin. The list comes from the server, so a failed refresh only adds a banner: what was
 * loaded before stays on screen. The screen asks for a refresh each time it opens, so coming back from a record shows
 * what changed.
 */
@HiltViewModel
class FailedSyncListViewModel @Inject constructor(
    private val repository: AdminSyncResolutionRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(FailedSyncListUiState())
    val uiState: StateFlow<FailedSyncListUiState> = _uiState.asStateFlow()

    private var running = false

    fun refresh() {
        if (running) return // one request at a time
        running = true
        _uiState.value = _uiState.value.copy(loading = true)
        viewModelScope.launch {
            _uiState.value = when (val result = repository.attention()) {
                is AdminResult.Success -> FailedSyncListUiState(loading = false, loaded = true, items = result.value)
                AdminResult.Offline -> _uiState.value.copy(loading = false, notice = AdminNotice.OFFLINE)
                AdminResult.Denied -> _uiState.value.copy(loading = false, notice = AdminNotice.DENIED)
                else -> _uiState.value.copy(loading = false, notice = AdminNotice.FAILED)
            }
            running = false
        }
    }
}

// ── Screen ─────────────────────────────────────────────────────────────────────

@Composable
fun FailedSyncListRoute(
    onBack: () -> Unit,
    onOpen: (SyncForm, String) -> Unit,
    viewModel: FailedSyncListViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    LaunchedEffect(Unit) { viewModel.refresh() }
    FailedSyncListScreen(state, onRefresh = viewModel::refresh, onBack = onBack, onOpen = onOpen)
}

@Composable
fun FailedSyncListScreen(
    state: FailedSyncListUiState,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
    onOpen: (SyncForm, String) -> Unit
) = CBOCollectorTheme {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SaColors.Surface)
            .padding(20.dp, 20.dp, 20.dp, 0.dp)
            .testTag(FailedSyncTags.LIST_SCREEN)
    ) {
        ScreenHeader(
            title = stringResource(AdminDestination.FAILED_SYNC.title),
            subtitle = if (state.loaded && state.items.isNotEmpty()) stringResource(R.string.admin_failed_summary, state.items.size) else null,
            onBack = onBack,
            modifier = Modifier.padding(bottom = 12.dp),
            trailing = { RefreshButton(state.loading, onRefresh) }
        )

        state.notice?.let { ListNotice(it, hasItems = state.loaded) }

        when {
            !state.loaded -> if (state.loading) {
                Text(
                    stringResource(R.string.admin_failed_loading),
                    fontFamily = Figtree, fontSize = 14.sp, color = SaColors.Muted,
                    modifier = Modifier.testTag(FailedSyncTags.LOADING)
                )
            }
            state.items.isEmpty() -> EmptyState()
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().testTag(FailedSyncTags.LIST),
                contentPadding = PaddingValues(top = 6.dp, bottom = 26.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(state.items, key = { "${it.form}/${it.id}" }) { ItemRow(it, onOpen) }
            }
        }
    }
}

@Composable
private fun RefreshButton(loading: Boolean, onRefresh: () -> Unit) {
    FilledPillButton(
        onClick = onRefresh,
        enabled = !loading,
        contentPadding = PaddingValues(vertical = 9.dp, horizontal = 16.dp),
        modifier = Modifier.testTag(FailedSyncTags.REFRESH)
    ) {
        Text(
            stringResource(if (loading) R.string.admin_failed_refreshing else R.string.admin_failed_refresh),
            fontFamily = Figtree, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = SaColors.Ink
        )
    }
}

@Composable
private fun ListNotice(notice: AdminNotice, hasItems: Boolean) {
    val message = stringResource(
        when (notice) {
            AdminNotice.OFFLINE -> if (hasItems) R.string.admin_failed_notice_offline_kept else R.string.admin_failed_notice_offline
            AdminNotice.DENIED -> R.string.admin_failed_notice_denied
            AdminNotice.FAILED -> if (hasItems) R.string.admin_failed_notice_failed_kept else R.string.admin_failed_notice_failed
        }
    )
    Banner(message, Modifier.padding(bottom = 10.dp).testTag(FailedSyncTags.NOTICE))
}

/** A warning strip that never blocks what is already on screen. */
@Composable
internal fun Banner(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        fontFamily = Figtree, fontSize = 12.5.sp, lineHeight = 18.sp, color = SaColors.TagWarnText,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SaColors.TagWarnBg)
            .padding(14.dp)
    )
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(SaColors.White)
            .padding(20.dp)
            .testTag(FailedSyncTags.EMPTY)
            .semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            stringResource(R.string.admin_failed_empty_title),
            fontFamily = Poppins, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, color = SaColors.Ink
        )
        Text(
            stringResource(R.string.admin_failed_empty_body),
            fontFamily = Figtree, fontSize = 14.sp, lineHeight = 21.sp, color = SaColors.Muted
        )
    }
}

@Composable
private fun ItemRow(item: AttentionItem, onOpen: (SyncForm, String) -> Unit) {
    CardButton(
        onClick = { onOpen(item.form, item.id) },
        modifier = Modifier
            .fillMaxWidth()
            .testTag(FailedSyncTags.item(item.id))
            .semantics(mergeDescendants = true) {},
        contentPadding = PaddingValues(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    item.label,
                    fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = SaColors.Ink,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)
                )
                TagBadge(stringResource(item.state.labelRes()), item.state.palette())
            }
            Text(
                listOfNotNull(
                    stringResource(item.form.labelRes()),
                    attemptsText(item.attempts),
                    item.submittedBy?.let { stringResource(R.string.admin_failed_submitted_by, it) }
                ).joinToString(" · "),
                fontFamily = Figtree, fontSize = 12.sp, color = SaColors.MutedLight
            )
            Text(
                item.error ?: stringResource(R.string.admin_failed_no_error),
                fontFamily = Figtree, fontSize = 12.5.sp, lineHeight = 18.sp, color = SaColors.Muted,
                maxLines = 2, overflow = TextOverflow.Ellipsis
            )
        }
    }
}
