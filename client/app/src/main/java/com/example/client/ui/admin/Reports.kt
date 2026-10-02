package com.example.client.ui.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.client.R
import com.example.client.data.local.entity.DecisionOutcome
import com.example.client.data.repository.CboCollectionRepository
import com.example.client.data.repository.VettingRepository
import com.example.client.ui.theme.CBOCollectorTheme
import com.example.client.ui.theme.Figtree
import com.example.client.ui.theme.Poppins
import com.example.client.ui.theme.SaColors
import com.example.client.ui.vetting.latestDecisionByRecord
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

object ReportsTags {
    const val SCREEN = "screen_reports"
    const val COLLECTIONS = "reports_collections"
    const val APPROVED = "reports_approved"
    const val FLAGGED = "reports_flagged"
    const val DENIED = "reports_denied"
}

/**
 * The Admin's figures. Collections are every collection stored on this device; the three vetting figures count each
 * record once, by its current decision (the newest, if the officer changed their mind), which is how the Vetting list
 * counts them too. "Denied" is the Reject outcome.
 */
data class ReportsUiState(
    val collections: Int = 0,
    val approved: Int = 0,
    val flagged: Int = 0,
    val denied: Int = 0
) {
    val vettings: Int get() = approved + flagged + denied
}

@HiltViewModel
class ReportsViewModel @Inject constructor(
    collections: CboCollectionRepository,
    vetting: VettingRepository
) : ViewModel() {
    val uiState = combine(collections.observeAll(), vetting.observeDecisions()) { stored, decisions ->
        val current = latestDecisionByRecord(decisions).values
        ReportsUiState(
            collections = stored.size,
            approved = current.count { it.outcome == DecisionOutcome.APPROVE },
            flagged = current.count { it.outcome == DecisionOutcome.FLAG },
            denied = current.count { it.outcome == DecisionOutcome.REJECT }
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReportsUiState())
}

@Composable
fun ReportsRoute(viewModel: ReportsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()
    ReportsScreen(state)
}

/** The demo's Reports tab: one hero figure and a row for each of the others. */
@Composable
fun ReportsScreen(state: ReportsUiState) = CBOCollectorTheme {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SaColors.Surface)
            .verticalScroll(rememberScrollState())
            .padding(top = 20.dp)
            .testTag(ReportsTags.SCREEN)
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 22.dp)
                .padding(bottom = 14.dp)
        ) {
            Text(
                stringResource(R.string.reports_title),
                fontFamily = Poppins, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, color = SaColors.Ink
            )
            Text(
                stringResource(R.string.reports_subtitle),
                fontFamily = Figtree, fontSize = 12.5.sp, color = SaColors.MutedLight,
                modifier = Modifier.padding(top = 3.dp)
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp)
                .padding(bottom = 18.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(SaColors.Ink)
                .padding(18.dp, 20.dp)
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    state.collections.toString(),
                    fontFamily = Poppins, fontWeight = FontWeight.SemiBold, fontSize = 40.sp, lineHeight = 44.sp, color = SaColors.Cream,
                    modifier = Modifier.testTag(ReportsTags.COLLECTIONS)
                )
                Text(
                    stringResource(R.string.reports_collections),
                    fontFamily = Figtree, fontSize = 14.sp, color = SaColors.Cream.copy(alpha = 0.7f),
                    modifier = Modifier.padding(start = 8.dp, bottom = 5.dp)
                )
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ReportRow(stringResource(R.string.reports_approved), state.approved, ReportsTags.APPROVED)
            ReportRow(stringResource(R.string.reports_flagged), state.flagged, ReportsTags.FLAGGED)
            ReportRow(stringResource(R.string.reports_denied), state.denied, ReportsTags.DENIED)
        }
        Text(
            stringResource(R.string.reports_note),
            fontFamily = Figtree, fontSize = 12.5.sp, lineHeight = 19.sp, color = SaColors.LinkGold,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp)
                .padding(top = 18.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(SaColors.YellowTickBg)
                .padding(14.dp, 14.dp, 18.dp, 14.dp)
        )
        Box(Modifier.size(1.dp, 26.dp))
    }
}

@Composable
private fun ReportRow(label: String, value: Int, tag: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SaColors.White)
            .border(1.dp, SaColors.inkAlpha(0.12f), RoundedCornerShape(12.dp))
            .padding(17.dp, 15.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontFamily = Figtree, fontSize = 13.5.sp, color = SaColors.Muted)
        Text(
            value.toString(),
            fontFamily = Poppins, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, color = SaColors.LinkGold,
            modifier = Modifier.testTag(tag)
        )
    }
}
