package com.example.client.ui.cbo

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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.client.R
import com.example.client.auth.SessionManager
import com.example.client.data.repository.CboCollectionRepository
import com.example.client.ui.components.CardButton
import com.example.client.ui.components.ConnectivityPill
import com.example.client.ui.components.EyebrowLabel
import com.example.client.ui.components.GreetingHeader
import com.example.client.ui.components.TagBadge
import com.example.client.ui.theme.CBOCollectorTheme
import com.example.client.ui.theme.Figtree
import com.example.client.ui.theme.GlyphPaths
import com.example.client.ui.theme.Poppins
import com.example.client.ui.theme.SaColors
import com.example.client.ui.theme.StrokeIcon
import com.example.client.ui.util.displayName
import com.example.client.ui.util.initialsOf
import com.example.client.ui.util.isSameDay
import com.example.client.ui.util.rememberIsOnline
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

object CboHomeTags {
    const val SCREEN = "screen_cbo_home"
    const val START = "cbo_home_start"
    const val PAST = "cbo_home_past"
    const val TODAY_EMPTY = "cbo_home_today_empty"
    fun today(id: String) = "cbo_home_today_$id"
}

data class CboHomeUiState(
    /** The username the collector signed in with, or null for a session restored from before it was kept. */
    val username: String? = null,
    val submissions: SubmissionsUiState = SubmissionsUiState()
)

/** Real values only: who is signed in, and what this device holds. Nothing here is sample data. */
@HiltViewModel
class CboHomeViewModel @Inject constructor(
    repository: CboCollectionRepository,
    sessionManager: SessionManager
) : ViewModel() {
    val uiState = repository.observeSubmissions(sessionManager.username())
        .map { CboHomeUiState(username = sessionManager.username(), submissions = it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CboHomeUiState(username = sessionManager.username()))
}

@Composable
fun CboHomeRoute(
    onStartCollection: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenQueue: () -> Unit,
    viewModel: CboHomeViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    CboHomeScreen(state, onStartCollection, onOpenHistory, onOpenQueue)
}

/** The demo's "Runs" home: a greeting, whether the device is online, the two things a collector does, and today's work. */
@Composable
fun CboHomeScreen(
    state: CboHomeUiState,
    onStartCollection: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenQueue: () -> Unit
) = CBOCollectorTheme {
    val online = rememberIsOnline()
    val today = remember(state.submissions) { state.submissions.items.filter { isSameDay(it.createdAt) } }
    val dateText = remember { SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(Date()) }
    val name = displayName(state.username)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SaColors.Surface)
            .verticalScroll(rememberScrollState())
            .testTag(CboHomeTags.SCREEN)
    ) {
        GreetingHeader(
            date = dateText,
            title = if (name != null) stringResource(R.string.greeting_hello, name) else stringResource(R.string.greeting_hello_anonymous),
            subtitle = stringResource(R.string.role_cbo_collection),
            initials = initialsOf(state.username)
        )
        ConnectivityPill(online = online, failedCount = state.submissions.failed, onOpenQueue = onOpenQueue)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            CardButton(
                onClick = onStartCollection,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(CboHomeTags.START),
                containerColor = SaColors.Yellow,
                borderColor = null,
                shape = RoundedCornerShape(16.dp),
                contentPadding = PaddingValues(20.dp)
            ) {
                HomeCardText(
                    title = stringResource(R.string.home_start_title),
                    body = stringResource(R.string.home_start_body),
                    bodyColor = SaColors.Ink.copy(alpha = 0.85f)
                )
            }
            CardButton(
                onClick = onOpenHistory,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(CboHomeTags.PAST),
                shape = RoundedCornerShape(16.dp),
                borderColor = SaColors.inkAlpha(0.14f),
                contentPadding = PaddingValues(20.dp)
            ) {
                HomeCardText(
                    title = stringResource(R.string.home_past_title),
                    body = stringResource(R.string.home_past_body),
                    bodyColor = SaColors.Muted
                )
            }
        }
        EyebrowLabel(
            stringResource(R.string.home_today, today.size),
            modifier = Modifier.padding(24.dp, 24.dp, 24.dp, 8.dp)
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (today.isEmpty()) {
                Text(
                    stringResource(R.string.home_today_empty),
                    fontFamily = Figtree, fontSize = 13.sp, color = SaColors.MutedLight,
                    modifier = Modifier.testTag(CboHomeTags.TODAY_EMPTY)
                )
            }
            today.forEach { item ->
                val style = item.display.style()
                CardButton(
                    onClick = onOpenHistory,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(CboHomeTags.today(item.id)),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(style.palette.bg),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(item.createdAt)),
                            fontFamily = Figtree, fontWeight = FontWeight.Bold, fontSize = 11.5.sp, color = style.palette.text
                        )
                    }
                    Column(modifier = Modifier
                        .weight(1f)
                        .padding(start = 13.dp)) {
                        Text(item.donorName, fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 14.5.sp, color = SaColors.Ink)
                        Text(
                            stringResource(R.string.sync_row_attachments, item.signatureCount, item.photoCount),
                            fontFamily = Figtree, fontSize = 12.sp, color = SaColors.MutedLight
                        )
                    }
                    TagBadge(stringResource(style.label), style.palette)
                }
            }
        }
        Box(Modifier.size(1.dp, 26.dp))
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.HomeCardText(
    title: String,
    body: String,
    bodyColor: androidx.compose.ui.graphics.Color
) {
    Column(modifier = Modifier.weight(1f)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, fontFamily = Poppins, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, color = SaColors.Ink)
            StrokeIcon(pathData = GlyphPaths.ArrowRight, tint = SaColors.Ink, modifier = Modifier.size(22.dp))
        }
        Text(body, fontFamily = Figtree, fontSize = 13.sp, color = bodyColor, modifier = Modifier.padding(top = 6.dp))
    }
}
