package com.example.client.ui.cbo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.client.R
import com.example.client.auth.SessionManager
import com.example.client.data.repository.CboCollectionRepository
import com.example.client.sync.CboSyncTrigger
import com.example.client.ui.components.SyncTabScreen
import com.example.client.ui.theme.CBOCollectorTheme
import com.example.client.ui.util.rememberIsOnline
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** What the CBO Sync tab can do: the same sync the app already runs in the background, and signing out. */
@HiltViewModel
class CboSyncViewModel @Inject constructor(
    repository: CboCollectionRepository,
    private val syncTrigger: CboSyncTrigger,
    private val sessionManager: SessionManager
) : ViewModel() {
    val uiState = repository.observeSubmissions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SubmissionsUiState())

    /** Queues a sync now; it waits for a network if there is none. */
    fun syncNow() = syncTrigger.syncCboCollectionsNow()

    fun signOut() = sessionManager.endSession()
}

@Composable
fun CboSyncRoute(viewModel: CboSyncViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()
    CboSyncScreen(state, onSync = viewModel::syncNow, onSignOut = viewModel::signOut)
}

/**
 * The demo's Sync tab for a collector: what on this device is not yet on the server, with the same status wording and
 * failure explanations the collector already had, a button to send it now, and sign out.
 */
@Composable
fun CboSyncScreen(
    state: SubmissionsUiState,
    onSync: () -> Unit,
    onSignOut: () -> Unit
) = CBOCollectorTheme {
    val online = rememberIsOnline()
    val waiting = state.waiting
    SyncTabScreen(
        subtitle = if (waiting.isEmpty()) stringResource(R.string.sync_all_clear) else stringResource(R.string.sync_waiting, waiting.size),
        online = online,
        failedMessage = if (state.failed > 0) stringResource(R.string.submissions_panel_failed, state.failed) else null,
        syncLabel = when {
            state.sendable == 0 -> stringResource(R.string.sync_all_synced)
            online -> stringResource(R.string.sync_now, state.sendable)
            else -> stringResource(R.string.sync_retry_online)
        },
        syncEnabled = online && state.sendable > 0,
        onSync = onSync,
        onSignOut = onSignOut
    ) {
        waiting.forEach { SubmissionRow(it) }
    }
}
