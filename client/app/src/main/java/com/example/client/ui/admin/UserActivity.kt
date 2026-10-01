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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.client.R
import com.example.client.auth.UserRole
import com.example.client.data.repository.ActivityFilter
import com.example.client.data.repository.ActivityItem
import com.example.client.data.repository.AdminResult
import com.example.client.data.repository.AdminUserActivityRepository
import com.example.client.data.repository.SyncForm
import com.example.client.ui.components.FilledPillButton
import com.example.client.ui.components.OutlinePillButton
import com.example.client.ui.components.SaInputField
import com.example.client.ui.components.ScreenHeader
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
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject

object UserActivityTags {
    const val SCREEN = "user_activity"
    const val REFRESH = "user_activity_refresh"
    const val USER = "user_activity_user"
    const val FROM = "user_activity_from"
    const val TO = "user_activity_to"
    const val DATE_ERROR = "user_activity_date_error"
    const val APPLY = "user_activity_apply"
    const val CLEAR = "user_activity_clear"
    const val WINDOW = "user_activity_window"
    const val NOTICE = "user_activity_notice"
    const val LOADING = "user_activity_loading"
    const val EMPTY = "user_activity_empty"
    const val LIST = "user_activity_items"
    const val MORE = "user_activity_more"

    /** The role chip for a role, or [ROLE_ANY] for "everyone". */
    fun role(role: UserRole?) = "user_activity_role_${role?.name?.lowercase() ?: ROLE_ANY}"
    const val ROLE_ANY = "any"

    fun item(form: SyncForm, id: String) = "user_activity_item_${form.name.lowercase()}_$id"
}

/** A day the server will accept: `yyyy-MM-dd`, a real calendar date, years 2000 to 2100. */
fun isValidActivityDate(text: String): Boolean {
    if (!Regex("""\d{4}-\d{2}-\d{2}""").matches(text)) return false
    if (text.substring(0, 4).toInt() !in 2000..2100) return false
    val format = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false; timeZone = TimeZone.getTimeZone("UTC") }
    return try {
        format.parse(text) != null
    } catch (e: java.text.ParseException) {
        false
    }
}

data class UserActivityUiState(
    // What is typed in the filter boxes (the date and user boxes only take effect on Apply; the role chips apply at once).
    val user: String = "",
    val role: UserRole? = null,
    val from: String = "",
    val to: String = "",
    val dateError: Boolean = false,

    // What the list below shows.
    val applied: ActivityFilter = ActivityFilter(),
    val items: List<ActivityItem> = emptyList(),
    val totalCount: Int = 0,
    val hasMore: Boolean = false,
    /** The dates the server applied, including its default recent window when none were asked for. */
    val windowFrom: String? = null,
    val windowTo: String? = null,

    val loading: Boolean = true,
    /** False until a first answer, so an empty list is not announced before it is known to be empty. */
    val loaded: Boolean = false,
    val loadingMore: Boolean = false,
    val notice: AdminNotice? = null
)

/**
 * Who submitted or vetted what, and when. Read-only. A page at a time, newest first; a failed load only adds a banner and
 * leaves what was already shown. A load that has been overtaken by a newer filter is dropped, so a slow answer for an old
 * filter can never appear under a new one.
 */
@HiltViewModel
class UserActivityViewModel @Inject constructor(
    private val repository: AdminUserActivityRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(UserActivityUiState())
    val uiState: StateFlow<UserActivityUiState> = _uiState.asStateFlow()

    private var generation = 0
    private var page = 0

    init {
        apply()
    }

    fun onUserChange(value: String) = _uiState.update { it.copy(user = value) }

    fun onFromChange(value: String) = _uiState.update { it.copy(from = value, dateError = false) }

    fun onToChange(value: String) = _uiState.update { it.copy(to = value, dateError = false) }

    fun onRoleChange(role: UserRole?) {
        _uiState.update { it.copy(role = role) }
        apply()
    }

    fun clear() {
        _uiState.update { it.copy(user = "", role = null, from = "", to = "", dateError = false) }
        apply()
    }

    /** Looks up what the filter boxes say, from the first page. Dates the server would refuse are caught here. */
    fun apply() {
        val state = _uiState.value
        val from = state.from.trim()
        val to = state.to.trim()
        if ((from.isNotEmpty() && !isValidActivityDate(from)) || (to.isNotEmpty() && !isValidActivityDate(to)) ||
            (from.isNotEmpty() && to.isNotEmpty() && from > to)
        ) {
            _uiState.update { it.copy(dateError = true) }
            return
        }
        val filter = ActivityFilter(user = state.user.trim().ifEmpty { null }, role = state.role, from = from.ifEmpty { null }, to = to.ifEmpty { null })
        val mine = ++generation
        _uiState.update { it.copy(loading = true, loadingMore = false, dateError = false) }
        viewModelScope.launch {
            val result = repository.page(filter, 1)
            if (mine != generation) return@launch
            when (result) {
                is AdminResult.Success -> {
                    page = 1
                    val p = result.value
                    _uiState.update {
                        it.copy(
                            applied = filter, items = p.items, totalCount = p.totalCount, hasMore = p.hasMore,
                            windowFrom = p.from, windowTo = p.to, loading = false, loaded = true, notice = null
                        )
                    }
                }
                else -> _uiState.update { it.copy(loading = false, notice = result.toNotice()) }
            }
        }
    }

    /** Adds the next page of the filter the list is showing. */
    fun loadMore() {
        val state = _uiState.value
        if (!state.hasMore || state.loading || state.loadingMore) return
        val mine = generation
        _uiState.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            val result = repository.page(state.applied, page + 1)
            if (mine != generation) return@launch
            when (result) {
                is AdminResult.Success -> {
                    page += 1
                    _uiState.update {
                        val known = it.items.map { item -> item.form to item.id }.toSet()
                        it.copy(
                            items = it.items + result.value.items.filterNot { item -> (item.form to item.id) in known },
                            totalCount = result.value.totalCount, hasMore = result.value.hasMore, loadingMore = false, notice = null
                        )
                    }
                }
                else -> _uiState.update { it.copy(loadingMore = false, notice = result.toNotice()) }
            }
        }
    }

    private fun AdminResult<*>.toNotice(): AdminNotice = when (this) {
        AdminResult.Offline -> AdminNotice.OFFLINE
        AdminResult.Denied -> AdminNotice.DENIED
        else -> AdminNotice.FAILED
    }
}

// ── Screen ─────────────────────────────────────────────────────────────────────

@Composable
fun UserActivityRoute(onBack: () -> Unit, viewModel: UserActivityViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()
    UserActivityScreen(
        state, onBack = onBack, onRefresh = viewModel::apply, onUserChange = viewModel::onUserChange,
        onRoleChange = viewModel::onRoleChange, onFromChange = viewModel::onFromChange, onToChange = viewModel::onToChange,
        onApply = viewModel::apply, onClear = viewModel::clear, onLoadMore = viewModel::loadMore
    )
}

@Composable
fun UserActivityScreen(
    state: UserActivityUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onUserChange: (String) -> Unit,
    onRoleChange: (UserRole?) -> Unit,
    onFromChange: (String) -> Unit,
    onToChange: (String) -> Unit,
    onApply: () -> Unit,
    onClear: () -> Unit,
    onLoadMore: () -> Unit
) = CBOCollectorTheme {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SaColors.Surface)
            .padding(20.dp, 20.dp, 20.dp, 0.dp)
            .testTag(UserActivityTags.SCREEN)
    ) {
        ScreenHeader(
            title = stringResource(AdminDestination.USER_ACTIVITY.title),
            onBack = onBack,
            modifier = Modifier.padding(bottom = 12.dp),
            trailing = { RefreshButton(state.loading, onRefresh) }
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag(UserActivityTags.LIST),
            contentPadding = PaddingValues(bottom = 26.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item { Filters(state, onUserChange, onRoleChange, onFromChange, onToChange, onApply, onClear) }

            state.notice?.let { notice -> item { ActivityNotice(notice, hasItems = state.loaded) } }

            when {
                !state.loaded -> if (state.loading) item {
                    Text(
                        stringResource(R.string.admin_activity_loading),
                        fontFamily = Figtree, fontSize = 14.sp, color = SaColors.Muted,
                        modifier = Modifier.testTag(UserActivityTags.LOADING)
                    )
                }
                else -> {
                    item { WindowSummary(state) }
                    if (state.items.isEmpty()) item { EmptyState() }
                    else {
                        items(state.items, key = { "${it.form}/${it.id}" }) { ActivityRow(it) }
                        if (state.hasMore) item { MoreButton(state.loadingMore, onLoadMore) }
                    }
                }
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
        modifier = Modifier.testTag(UserActivityTags.REFRESH)
    ) {
        Text(
            stringResource(if (loading) R.string.admin_failed_refreshing else R.string.admin_failed_refresh),
            fontFamily = Figtree, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = SaColors.Ink
        )
    }
}

@Composable
private fun Filters(
    state: UserActivityUiState,
    onUserChange: (String) -> Unit,
    onRoleChange: (UserRole?) -> Unit,
    onFromChange: (String) -> Unit,
    onToChange: (String) -> Unit,
    onApply: () -> Unit,
    onClear: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SaColors.White)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Label(stringResource(R.string.admin_activity_filter_user))
        SaInputField(
            value = state.user, onValueChange = onUserChange,
            placeholder = stringResource(R.string.admin_activity_filter_user_placeholder), fieldTestTag = UserActivityTags.USER
        )

        Label(stringResource(R.string.admin_activity_filter_role))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            RoleChip(null, state.role == null, onRoleChange, Modifier.weight(1f))
            UserRole.values().forEach { RoleChip(it, state.role == it, onRoleChange, Modifier.weight(1f)) }
        }

        Label(stringResource(R.string.admin_activity_filter_dates))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                SaInputField(
                    value = state.from, onValueChange = onFromChange, placeholder = stringResource(R.string.admin_activity_filter_from),
                    keyboardType = KeyboardType.Number, isError = state.dateError, fieldTestTag = UserActivityTags.FROM
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                SaInputField(
                    value = state.to, onValueChange = onToChange, placeholder = stringResource(R.string.admin_activity_filter_to),
                    keyboardType = KeyboardType.Number, isError = state.dateError, fieldTestTag = UserActivityTags.TO
                )
            }
        }
        if (state.dateError) {
            Text(
                stringResource(R.string.admin_activity_date_error),
                fontFamily = Figtree, fontSize = 12.5.sp, lineHeight = 18.sp, color = SaColors.Error,
                modifier = Modifier.testTag(UserActivityTags.DATE_ERROR)
            )
        } else {
            Text(
                stringResource(R.string.admin_activity_dates_hint),
                fontFamily = Figtree, fontSize = 12.sp, lineHeight = 17.sp, color = SaColors.MutedLight
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            FilledPillButton(
                onClick = onApply,
                modifier = Modifier.weight(1f).testTag(UserActivityTags.APPLY),
                contentPadding = PaddingValues(vertical = 11.dp, horizontal = 16.dp)
            ) {
                Text(stringResource(R.string.admin_activity_apply), fontFamily = Figtree, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = SaColors.Ink)
            }
            OutlinePillButton(
                onClick = onClear,
                modifier = Modifier.weight(1f).testTag(UserActivityTags.CLEAR),
                contentPadding = PaddingValues(vertical = 11.dp, horizontal = 16.dp)
            ) {
                Text(stringResource(R.string.admin_activity_clear), fontFamily = Figtree, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = SaColors.Ink)
            }
        }
    }
}

@Composable
private fun Label(text: String) =
    Text(text, fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = SaColors.Ink)

@Composable
private fun RoleChip(role: UserRole?, selected: Boolean, onSelect: (UserRole?) -> Unit, modifier: Modifier) {
    val label = stringResource(role.chipLabel())
    val padding = PaddingValues(vertical = 8.dp, horizontal = 4.dp)
    val tag = modifier.testTag(UserActivityTags.role(role)).semantics(mergeDescendants = true) {}
    if (selected) {
        FilledPillButton(onClick = { onSelect(role) }, modifier = tag, contentPadding = padding) { ChipText(label) }
    } else {
        OutlinePillButton(onClick = { onSelect(role) }, modifier = tag, contentPadding = padding) { ChipText(label) }
    }
}

@Composable
private fun ChipText(text: String) =
    Text(text, fontFamily = Figtree, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = SaColors.Ink, maxLines = 1)

private fun UserRole?.chipLabel(): Int = when (this) {
    null -> R.string.admin_activity_role_any
    UserRole.CBO_COLLECTION -> R.string.admin_activity_role_collector
    UserRole.VETTING -> R.string.admin_activity_role_vetting
    UserRole.ADMIN -> R.string.admin_activity_role_admin
}

@Composable
private fun WindowSummary(state: UserActivityUiState) {
    val window = when {
        state.windowFrom != null && state.windowTo != null -> stringResource(R.string.admin_activity_window_between, state.windowFrom, state.windowTo)
        state.windowFrom != null -> stringResource(R.string.admin_activity_window_since, state.windowFrom)
        state.windowTo != null -> stringResource(R.string.admin_activity_window_until, state.windowTo)
        else -> stringResource(R.string.admin_activity_window_all)
    }
    Text(
        window + " · " + stringResource(R.string.admin_activity_count, state.totalCount),
        fontFamily = Figtree, fontSize = 12.5.sp, color = SaColors.MutedLight,
        modifier = Modifier.testTag(UserActivityTags.WINDOW)
    )
}

@Composable
private fun ActivityNotice(notice: AdminNotice, hasItems: Boolean) {
    val message = stringResource(
        when (notice) {
            AdminNotice.OFFLINE -> if (hasItems) R.string.admin_failed_notice_offline_kept else R.string.admin_failed_notice_offline
            AdminNotice.DENIED -> R.string.admin_failed_notice_denied
            AdminNotice.FAILED -> if (hasItems) R.string.admin_failed_notice_failed_kept else R.string.admin_failed_notice_failed
        }
    )
    Banner(message, Modifier.testTag(UserActivityTags.NOTICE))
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SaColors.White)
            .padding(20.dp)
            .testTag(UserActivityTags.EMPTY)
            .semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            stringResource(R.string.admin_activity_empty_title),
            fontFamily = Poppins, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, color = SaColors.Ink
        )
        Text(
            stringResource(R.string.admin_activity_empty_body),
            fontFamily = Figtree, fontSize = 14.sp, lineHeight = 21.sp, color = SaColors.Muted
        )
    }
}

@Composable
private fun ActivityRow(item: ActivityItem) {
    val who = item.user ?: stringResource(R.string.admin_activity_unknown_user)
    val role = item.role?.let { stringResource(it.chipLabel()) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(SaColors.White)
            .padding(16.dp)
            .testTag(UserActivityTags.item(item.form, item.id))
            .semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            stringResource(
                if (item.form == SyncForm.CBO_COLLECTION) R.string.admin_activity_did_collection else R.string.admin_activity_did_decision,
                who
            ),
            fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 14.5.sp, color = SaColors.Ink
        )
        Text(item.label, fontFamily = Figtree, fontSize = 13.sp, lineHeight = 19.sp, color = SaColors.Muted)
        Text(
            listOfNotNull(formatWhen(item.atMillis) ?: stringResource(R.string.admin_failed_not_recorded), role).joinToString(" · "),
            fontFamily = Figtree, fontSize = 12.sp, color = SaColors.MutedLight
        )
    }
}

@Composable
private fun MoreButton(loading: Boolean, onLoadMore: () -> Unit) {
    OutlinePillButton(
        onClick = { if (!loading) onLoadMore() },
        modifier = Modifier.fillMaxWidth().testTag(UserActivityTags.MORE)
    ) {
        Text(
            stringResource(if (loading) R.string.admin_activity_loading_more else R.string.admin_activity_more),
            fontFamily = Figtree, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = SaColors.Ink
        )
    }
}
