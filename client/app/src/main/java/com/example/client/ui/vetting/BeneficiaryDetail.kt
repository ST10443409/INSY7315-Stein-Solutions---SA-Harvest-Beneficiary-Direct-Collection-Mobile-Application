package com.example.client.ui.vetting

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.client.R
import com.example.client.data.local.entity.DecisionOutcome
import com.example.client.data.local.entity.FoodspaceBeneficiaryRecord
import com.example.client.data.local.entity.Tone
import com.example.client.data.local.entity.VettingDecision
import com.example.client.data.repository.VettingRecordsRepository
import com.example.client.data.repository.VettingRepository
import com.example.client.ui.components.AccordionSection
import com.example.client.ui.components.ErrorTagPalette
import com.example.client.ui.components.FilledPillButton
import com.example.client.ui.components.PillShape
import com.example.client.ui.components.ScreenHeader
import com.example.client.ui.components.TagBadge
import com.example.client.ui.theme.CBOCollectorTheme
import com.example.client.ui.theme.Figtree
import com.example.client.ui.theme.SaColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

object DetailTags {
    const val SCREEN = "vetting_detail"
    const val NOT_FOUND = "vetting_detail_not_found"
    const val EXPAND_ALL = "vetting_expand_all"
    const val COLLAPSE_ALL = "vetting_collapse_all"
    const val RECORD_DECISION = "vetting_record_decision"
    const val CURRENT_DECISION = "vetting_current_decision"
    const val BACK = "vetting_detail_back"
    fun field(sheetKey: String) = "vetting_field_$sheetKey"
}

sealed interface DetailUiState {
    /** The cache has not answered yet (a few milliseconds). Distinct from [NotFound] so the screen never flashes it. */
    object Loading : DetailUiState

    /** The record is not (or no longer) on this device. */
    object NotFound : DetailUiState

    data class Ready(val record: FoodspaceBeneficiaryRecord, val currentDecision: VettingDecision?) : DetailUiState
}

/** The record, and the officer's current decision on it, both read from the device so the screen works offline. */
@HiltViewModel
class BeneficiaryDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    records: VettingRecordsRepository,
    vetting: VettingRepository
) : ViewModel() {

    val recordId: String = checkNotNull(savedStateHandle[VETTING_RECORD_ARG]) { "The record id is a required navigation argument." }

    val uiState: StateFlow<DetailUiState> = combine(
        records.observeRecord(recordId),
        vetting.observeDecisionsFor(recordId)
    ) { record, decisions ->
        if (record == null) DetailUiState.NotFound else DetailUiState.Ready(record, decisions.firstOrNull())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DetailUiState.Loading)
}

const val VETTING_RECORD_ARG = "id"

// ── Screen ─────────────────────────────────────────────────────────────────────

@Composable
fun BeneficiaryDetailRoute(
    onBack: () -> Unit,
    onRecordDecision: (String) -> Unit,
    viewModel: BeneficiaryDetailViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    BeneficiaryDetailScreen(state, onBack, onRecordDecision = { onRecordDecision(viewModel.recordId) })
}

/**
 * All 54 fields of a beneficiary record in 11 accordion sections ([BENEFICIARY_SECTIONS]), read-only: Foodspace owns
 * this data. Any number of sections can be open at once; the first starts open. A bar at the bottom records the
 * officer's decision. Nothing here needs a connection.
 */
@Composable
fun BeneficiaryDetailScreen(
    state: DetailUiState,
    onBack: () -> Unit,
    onRecordDecision: () -> Unit,
    initiallyExpanded: Set<String> = setOf(BENEFICIARY_SECTIONS.first().id)
) = CBOCollectorTheme {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SaColors.Surface)
            .testTag(DetailTags.SCREEN)
    ) {
        when (state) {
            DetailUiState.Loading -> ScreenHeader(
                title = "", onBack = onBack, modifier = Modifier.padding(20.dp, 20.dp, 20.dp, 0.dp)
            )

            DetailUiState.NotFound -> {
                ScreenHeader(title = stringResource(R.string.vetting_title), onBack = onBack, modifier = Modifier.padding(20.dp, 20.dp, 20.dp, 0.dp))
                Text(
                    stringResource(R.string.vetting_detail_not_found),
                    fontFamily = Figtree, fontSize = 14.5.sp, lineHeight = 23.sp, color = SaColors.Muted,
                    modifier = Modifier.padding(20.dp).testTag(DetailTags.NOT_FOUND)
                )
            }

            is DetailUiState.Ready -> ReadyContent(state, onBack, onRecordDecision, initiallyExpanded)
        }
    }
}


@Composable
private fun androidx.compose.foundation.layout.ColumnScope.ReadyContent(
    state: DetailUiState.Ready,
    onBack: () -> Unit,
    onRecordDecision: () -> Unit,
    initiallyExpanded: Set<String>
) {
    val record = state.record
    var expanded by rememberSaveable(
        stateSaver = listSaver<Set<String>, String>(save = { it.toList() }, restore = { it.toSet() })
    ) { mutableStateOf(initiallyExpanded) }
    val allIds = BENEFICIARY_SECTIONS.map { it.id }.toSet()

    Column(
        modifier = Modifier
            .weight(1f)
            .verticalScroll(rememberScrollState())
            .padding(20.dp, 20.dp, 20.dp, 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        ScreenHeader(title = record.legalName, subtitle = record.province, onBack = onBack, modifier = Modifier.padding(bottom = 4.dp))

        DecisionSummary(state.currentDecision)

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            val allOpen = expanded.containsAll(allIds)
            val tag = if (allOpen) DetailTags.COLLAPSE_ALL else DetailTags.EXPAND_ALL
            Text(
                stringResource(if (allOpen) R.string.vetting_collapse_all else R.string.vetting_expand_all),
                fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = SaColors.LinkGold,
                modifier = Modifier
                    .clip(PillShape)
                    .clickable { expanded = if (allOpen) emptySet() else allIds }
                    .padding(horizontal = 10.dp, vertical = 8.dp)
                    .testTag(tag)
            )
        }

        val context = LocalContext.current
        BENEFICIARY_SECTIONS.forEach { section ->
            AccordionSection(
                id = section.id,
                title = stringResource(section.title),
                subtitle = pluralStringResource(R.plurals.vetting_field_count, section.fields.size, section.fields.size),
                expanded = section.id in expanded,
                onToggle = { expanded = if (section.id in expanded) expanded - section.id else expanded + section.id },
            ) {
                section.fields.forEachIndexed { index, spec ->
                    FieldRow(spec, spec.read(record), record, onOpenAddress = { openInMaps(context, it) })
                    if (index < section.fields.lastIndex) {
                        Spacer(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(1.dp).background(SaColors.Divider.copy(alpha = 0.6f)))
                    }
                }
            }
        }
    }

    // Pinned, so it is reachable however far the officer has scrolled.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SaColors.White)
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        FilledPillButton(
            onClick = onRecordDecision,
            modifier = Modifier.fillMaxWidth().testTag(DetailTags.RECORD_DECISION),
        ) {
            Text(
                stringResource(if (state.currentDecision == null) R.string.vetting_record_decision else R.string.vetting_change_decision),
                fontFamily = Figtree, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = SaColors.Ink
            )
        }
    }
}

@Composable
private fun DecisionSummary(decision: VettingDecision?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SaColors.SurfaceAlt)
            .padding(14.dp)
            .testTag(DetailTags.CURRENT_DECISION)
            .semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (decision == null) {
                Text(stringResource(R.string.vetting_no_decision_yet), fontFamily = Figtree, fontSize = 13.5.sp, color = SaColors.Muted)
            } else {
                Text(stringResource(R.string.vetting_current_decision), fontFamily = Figtree, fontSize = 13.5.sp, color = SaColors.Muted)
                DecisionBadge(decision.outcome)
            }
        }
        if (decision != null) {
            val sync = decision.syncDisplay()
            Text(
                stringResource(sync.hintRes()),
                fontFamily = Figtree, fontSize = 12.5.sp, lineHeight = 18.sp,
                color = if (sync.isFailed) SaColors.TagErrorText else SaColors.Muted
            )
        }
    }
}

@Composable
fun DecisionBadge(outcome: DecisionOutcome?) {
    when (outcome) {
        null -> TagBadge(stringResource(R.string.vetting_decision_none), Tone.NEW)
        DecisionOutcome.APPROVE -> TagBadge(stringResource(R.string.vetting_outcome_approve), Tone.OK)
        DecisionOutcome.REJECT -> TagBadge(stringResource(R.string.vetting_outcome_reject), ErrorTagPalette)
        DecisionOutcome.FLAG -> TagBadge(stringResource(R.string.vetting_outcome_flag), Tone.WARN)
    }
}

// ── One field ──────────────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun FieldRow(
    spec: FieldSpec,
    value: FieldValue,
    record: FoodspaceBeneficiaryRecord,
    onOpenAddress: (String) -> Unit
) {
    val label = stringResource(spec.label)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag(DetailTags.field(spec.sheetKey))
            .semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Text(label, fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 11.5.sp, color = SaColors.MutedLight)

        when (value) {
            is FieldValue.Text -> {
                val text = value.text?.trim().orEmpty()
                if (text.isEmpty()) MissingValue(stringResource(R.string.vetting_not_provided))
                else {
                    val shown = if (spec.kind == FieldKind.WHAT3WORDS) "///" + text.removePrefix("///") else text
                    ValueText(shown)
                    if (spec.kind == FieldKind.ADDRESS) {
                        val query = listOf(text, record.address2.orEmpty(), record.province).filter { it.isNotBlank() }.joinToString(", ")
                        Text(
                            stringResource(R.string.vetting_open_maps),
                            fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = SaColors.LinkGold,
                            modifier = Modifier.clickable { onOpenAddress(query) }.padding(vertical = 4.dp)
                        )
                    }
                }
            }

            is FieldValue.Number -> ValueText(value.value.toString())

            is FieldValue.YesNo ->
                if (value.value) TagBadge(stringResource(R.string.vetting_yes), Tone.OK)
                else TagBadge(stringResource(R.string.vetting_no), ErrorTagPalette)

            is FieldValue.Chips ->
                if (value.values.isEmpty()) MissingValue(stringResource(R.string.vetting_none_selected))
                else FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    value.values.forEach { Chip(it) }
                }

            is FieldValue.Date ->
                if (value.epochMillis == null) MissingValue(stringResource(R.string.vetting_not_recorded))
                else ValueText(formatFieldDate(value.epochMillis))

            is FieldValue.File -> {
                val reference = value.reference?.trim().orEmpty()
                if (reference.isEmpty()) MissingValue(stringResource(R.string.vetting_no_file))
                else {
                    TagBadge(stringResource(R.string.vetting_file_attached), Tone.OK)
                    Text(
                        reference, fontFamily = Figtree, fontSize = 12.sp, color = SaColors.Muted,
                        maxLines = 2, overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun ValueText(text: String) {
    Text(text, fontFamily = Figtree, fontSize = 15.sp, lineHeight = 22.sp, color = SaColors.Ink)
}

@Composable
private fun MissingValue(text: String) {
    Text(text, fontFamily = Figtree, fontSize = 14.sp, fontStyle = FontStyle.Italic, color = SaColors.Faint)
}

@Composable
private fun Chip(text: String) {
    Text(
        text, fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp, color = SaColors.InkSoft,
        modifier = Modifier
            .clip(PillShape)
            .background(SaColors.SurfaceAlt, PillShape)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    )
}

private fun openInMaps(context: android.content.Context, query: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(query)))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        // No maps app on this device: nothing to do, the address is still on screen.
    }
}
