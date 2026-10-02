package com.example.client.ui.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import com.example.client.auth.SessionManager
import com.example.client.data.repository.AdminSyncStatusRepository
import com.example.client.data.repository.SyncStatusResult
import com.example.client.data.repository.SyncStatusSnapshot
import com.example.client.ui.components.CardButton
import com.example.client.ui.components.EyebrowLabel
import com.example.client.ui.components.GreetingHeader
import com.example.client.ui.theme.AlertCircleGlyph
import com.example.client.ui.theme.CBOCollectorTheme
import com.example.client.ui.theme.Figtree
import com.example.client.ui.theme.GlyphPaths
import com.example.client.ui.theme.Poppins
import com.example.client.ui.theme.SaColors
import com.example.client.ui.theme.StrokeIcon
import com.example.client.ui.util.displayName
import com.example.client.ui.util.initialsOf
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

object AdminTags {
    const val DASHBOARD = "screen_admin_dashboard"
    const val HERO = "admin_overview_hero"
    const val NOTHING_TO_FLAG = "admin_overview_all_clear"

    /** The Overview entry for one destination. */
    fun entry(destination: AdminDestination) = "admin_entry_${destination.name.lowercase()}"

    /** One "needs attention" alert, by what it is about. */
    fun alert(kind: AdminAlertKind) = "admin_alert_${kind.name.lowercase()}"
}

/** The things the Overview can flag, each opening the screen where the Admin deals with it. */
enum class AdminAlertKind(val destination: AdminDestination, val warn: Boolean) {
    COLLECTIONS_NEED_ATTENTION(AdminDestination.FAILED_SYNC, warn = true),
    DECISIONS_NEED_ATTENTION(AdminDestination.FAILED_SYNC, warn = true),
    DUPLICATE_COLLECTIONS(AdminDestination.FAILED_SYNC, warn = true),
    WAITING_TO_SYNC(AdminDestination.SYNC_MONITOR, warn = false)
}

data class AdminAlert(val kind: AdminAlertKind, val count: Int)

data class AdminOverviewUiState(
    val username: String? = null,
    val loading: Boolean = true,
    /** The server's counts, kept while a refresh runs or fails. Null until the first answer. */
    val snapshot: SyncStatusSnapshot? = null,
    val notice: AdminNotice? = null
) {
    /** Records the server has stopped retrying, of either form. */
    val needsAttention: Int get() = snapshot?.let { it.cboCollections.needsAttention + it.vettingDecisions.needsAttention } ?: 0

    /** What the Overview flags, worst first. Empty when the server's counts show nothing wrong (or are not known yet). */
    val alerts: List<AdminAlert>
        get() {
            val s = snapshot ?: return emptyList()
            return listOf(
                AdminAlert(AdminAlertKind.COLLECTIONS_NEED_ATTENTION, s.cboCollections.needsAttention),
                AdminAlert(AdminAlertKind.DECISIONS_NEED_ATTENTION, s.vettingDecisions.needsAttention),
                AdminAlert(AdminAlertKind.DUPLICATE_COLLECTIONS, s.cboCollections.duplicates),
                AdminAlert(
                    AdminAlertKind.WAITING_TO_SYNC,
                    s.cboCollections.waiting + s.cboCollections.retrying + s.vettingDecisions.waiting + s.vettingDecisions.retrying
                )
            ).filter { it.count > 0 }
        }
}

/** Loads the server's own counts (the same ones the sync monitor shows) each time the Overview is opened. */
@HiltViewModel
class AdminOverviewViewModel @Inject constructor(
    private val repository: AdminSyncStatusRepository,
    sessionManager: SessionManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(AdminOverviewUiState(username = sessionManager.username()))
    val uiState: StateFlow<AdminOverviewUiState> = _uiState.asStateFlow()

    private var running = false

    fun refresh() {
        if (running) return // one request at a time
        running = true
        _uiState.value = _uiState.value.copy(loading = true)
        viewModelScope.launch {
            _uiState.value = when (val result = repository.load()) {
                is SyncStatusResult.Loaded -> _uiState.value.copy(loading = false, snapshot = result.snapshot, notice = null)
                SyncStatusResult.Offline -> _uiState.value.copy(loading = false, notice = AdminNotice.OFFLINE)
                SyncStatusResult.Denied -> _uiState.value.copy(loading = false, notice = AdminNotice.DENIED)
                SyncStatusResult.Failed -> _uiState.value.copy(loading = false, notice = AdminNotice.FAILED)
            }
            running = false
        }
    }
}

@Composable
fun AdminOverviewRoute(onOpen: (AdminDestination) -> Unit, viewModel: AdminOverviewViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()
    LaunchedEffect(Unit) { viewModel.refresh() }
    AdminOverviewScreen(state, onOpen)
}

/**
 * The Admin landing screen, in the demo's Overview layout: what needs attention, drawn from the server's own counts, and
 * the oversight sections. Reaching it is already restricted to the ADMIN role by the navigation graph; this screen only
 * lays it out.
 */
@Composable
fun AdminOverviewScreen(state: AdminOverviewUiState, onOpen: (AdminDestination) -> Unit) = CBOCollectorTheme {
    val dateText = remember { SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(Date()) }
    val name = displayName(state.username)
    val snapshot = state.snapshot

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SaColors.Surface)
            .verticalScroll(rememberScrollState())
            .testTag(AdminTags.DASHBOARD)
    ) {
        GreetingHeader(
            date = dateText,
            title = stringResource(R.string.admin_overview_title),
            subtitle = if (name != null) "$name · " + stringResource(R.string.role_admin) else stringResource(R.string.role_admin),
            initials = initialsOf(state.username)
        )

        // The hero card: opens the place to deal with what needs attention, or the monitor when nothing does.
        val needs = state.needsAttention
        CardButton(
            onClick = { onOpen(if (needs > 0) AdminDestination.FAILED_SYNC else AdminDestination.SYNC_MONITOR) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp)
                .padding(bottom = 14.dp)
                .testTag(AdminTags.HERO),
            containerColor = SaColors.Yellow,
            borderColor = null,
            shape = RoundedCornerShape(16.dp),
            contentPadding = PaddingValues(20.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        when {
                            snapshot == null && state.loading -> stringResource(R.string.admin_overview_hero_loading)
                            snapshot == null -> stringResource(R.string.admin_overview_hero_unknown)
                            needs > 0 -> stringResource(R.string.admin_overview_hero_attention, needs)
                            else -> stringResource(R.string.admin_overview_hero_clear)
                        },
                        fontFamily = Poppins, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, color = SaColors.Ink,
                        modifier = Modifier.weight(1f)
                    )
                    StrokeIcon(pathData = GlyphPaths.ArrowRight, tint = SaColors.Ink, modifier = Modifier.size(22.dp))
                }
                Text(
                    when {
                        snapshot == null && state.notice != null -> stringResource(state.notice.messageRes())
                        snapshot == null -> stringResource(R.string.admin_overview_hero_body_unknown)
                        needs > 0 -> stringResource(R.string.admin_overview_hero_body_attention)
                        else -> stringResource(R.string.admin_overview_hero_body_clear)
                    },
                    fontFamily = Figtree, fontSize = 13.sp, color = SaColors.Ink.copy(alpha = 0.85f),
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            StatTile(
                value = snapshot?.cboCollections?.total?.toString() ?: "–",
                label = stringResource(R.string.admin_overview_collections),
                dark = true,
                modifier = Modifier.weight(1f)
            )
            StatTile(
                value = snapshot?.vettingDecisions?.total?.toString() ?: "–",
                label = stringResource(R.string.admin_overview_decisions),
                dark = false,
                modifier = Modifier.weight(1f)
            )
        }

        EyebrowLabel(stringResource(R.string.admin_overview_attention), modifier = Modifier.padding(24.dp, 24.dp, 24.dp, 8.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (state.alerts.isEmpty()) {
                Text(
                    stringResource(if (snapshot == null) R.string.admin_overview_alerts_unknown else R.string.admin_overview_alerts_none),
                    fontFamily = Figtree, fontSize = 13.sp, color = SaColors.MutedLight,
                    modifier = Modifier.testTag(AdminTags.NOTHING_TO_FLAG)
                )
            }
            state.alerts.forEach { alert -> AlertCard(alert, onClick = { onOpen(alert.kind.destination) }) }
        }

        EyebrowLabel(stringResource(R.string.admin_group_oversight), modifier = Modifier.padding(24.dp, 24.dp, 24.dp, 8.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            AdminDestination.values().forEach { destination ->
                DestinationCard(destination, onClick = { onOpen(destination) })
            }
        }
        Box(Modifier.size(1.dp, 26.dp))
    }
}

@Composable
private fun StatTile(value: String, label: String, dark: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (dark) SaColors.Ink else SaColors.White)
            .padding(16.dp)
    ) {
        Text(
            value,
            fontFamily = Poppins, fontWeight = FontWeight.SemiBold, fontSize = 26.sp,
            color = if (dark) SaColors.Cream else SaColors.LinkGold
        )
        Text(
            label,
            fontFamily = Figtree, fontSize = 11.5.sp,
            color = if (dark) SaColors.Cream.copy(alpha = 0.72f) else SaColors.MutedLight,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

@Composable
private fun AlertCard(alert: AdminAlert, onClick: () -> Unit) {
    val (title, meta) = when (alert.kind) {
        AdminAlertKind.COLLECTIONS_NEED_ATTENTION ->
            stringResource(R.string.admin_alert_collections, alert.count) to stringResource(R.string.admin_alert_attention_meta)
        AdminAlertKind.DECISIONS_NEED_ATTENTION ->
            stringResource(R.string.admin_alert_decisions, alert.count) to stringResource(R.string.admin_alert_attention_meta)
        AdminAlertKind.DUPLICATE_COLLECTIONS ->
            stringResource(R.string.admin_alert_duplicates, alert.count) to stringResource(R.string.admin_alert_duplicates_meta)
        AdminAlertKind.WAITING_TO_SYNC ->
            stringResource(R.string.admin_alert_waiting, alert.count) to stringResource(R.string.admin_alert_waiting_meta)
    }
    CardButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(AdminTags.alert(alert.kind)),
        shape = RoundedCornerShape(12.dp),
        borderColor = SaColors.inkAlpha(0.12f),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(if (alert.kind.warn) SaColors.YellowTickBg else SaColors.AppBg),
            contentAlignment = Alignment.Center
        ) {
            AlertCircleGlyph(tint = if (alert.kind.warn) SaColors.LinkGold else SaColors.Muted, modifier = Modifier.size(16.dp))
        }
        Column(modifier = Modifier
            .weight(1f)
            .padding(start = 13.dp)) {
            Text(title, fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = SaColors.Ink)
            Text(meta, fontFamily = Figtree, fontSize = 11.5.sp, color = SaColors.MutedLight)
        }
        StrokeIcon(pathData = GlyphPaths.ChevronRight, tint = SaColors.Faint, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun DestinationCard(destination: AdminDestination, onClick: () -> Unit) {
    CardButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(AdminTags.entry(destination))
            .semantics(mergeDescendants = true) {},
        shape = RoundedCornerShape(12.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Column(modifier = Modifier
            .weight(1f)
            .padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(destination.title),
                fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 14.5.sp, color = SaColors.Ink
            )
            Text(
                stringResource(destination.description),
                fontFamily = Figtree, fontSize = 12.sp, lineHeight = 18.sp, color = SaColors.MutedLight
            )
        }
        StrokeIcon(pathData = GlyphPaths.ChevronRight, modifier = Modifier.size(18.dp), tint = SaColors.Faint)
    }
}

private fun AdminNotice.messageRes() = when (this) {
    AdminNotice.OFFLINE -> R.string.admin_notice_offline
    AdminNotice.DENIED -> R.string.admin_notice_denied
    AdminNotice.FAILED -> R.string.admin_notice_failed
}
