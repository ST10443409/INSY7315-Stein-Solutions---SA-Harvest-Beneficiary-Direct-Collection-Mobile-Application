package com.example.client.ui.vetting

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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.client.R
import com.example.client.auth.SessionManager
import com.example.client.data.local.entity.DecisionOutcome
import com.example.client.data.repository.VettingRecordsRepository
import com.example.client.data.repository.VettingRepository
import com.example.client.sync.VettingSyncTrigger
import com.example.client.ui.components.FilledPillButton
import com.example.client.ui.components.SaTextArea
import com.example.client.ui.components.ScreenHeader
import com.example.client.ui.theme.CBOCollectorTheme
import com.example.client.ui.theme.Figtree
import com.example.client.ui.theme.Poppins
import com.example.client.ui.theme.SaColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

object DecisionTags {
    const val SCREEN = "vetting_decision"
    const val NOTES = "vetting_decision_notes"
    const val SAVE = "vetting_decision_save"
    const val ERROR_CHOOSE = "vetting_decision_error_choose"
    const val SAVE_FAILED = "vetting_decision_save_failed"
    const val SAVED = "vetting_decision_saved"
    const val DONE = "vetting_decision_done"
    fun option(outcome: DecisionOutcome) = "vetting_decision_option_${outcome.name.lowercase()}"
}

data class DecisionUiState(
    val recordName: String? = null,
    val outcome: DecisionOutcome? = null,
    val notes: String = "",
    /** The officer tried to save without choosing. */
    val chooseError: Boolean = false,
    val isSaving: Boolean = false,
    val saveFailed: Boolean = false,
    val saved: Boolean = false,
    /** The decision already on this record, if any: saving a new one makes that one history. */
    val previous: DecisionOutcome? = null
)

/** Captures an officer's decision on one record and saves it locally. No network is involved. */
@HiltViewModel
class DecisionViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val records: VettingRecordsRepository,
    private val vetting: VettingRepository,
    private val session: SessionManager,
    private val syncTrigger: VettingSyncTrigger
) : ViewModel() {

    private val recordId: String = checkNotNull(savedStateHandle[VETTING_RECORD_ARG]) { "The record id is a required navigation argument." }

    private val _uiState = MutableStateFlow(DecisionUiState())
    val uiState: StateFlow<DecisionUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val name = records.observeRecord(recordId).first()?.legalName
            val previous = vetting.observeDecisionsFor(recordId).first().firstOrNull()?.outcome
            _uiState.update { it.copy(recordName = name, previous = previous) }
        }
    }

    fun onOutcomeChange(outcome: DecisionOutcome) =
        _uiState.update { it.copy(outcome = outcome, chooseError = false, saveFailed = false) }

    fun onNotesChange(value: String) =
        _uiState.update { it.copy(notes = value.take(MAX_NOTES_LENGTH), saveFailed = false) }

    fun onSave() {
        val state = _uiState.value
        if (state.isSaving || state.saved) return
        val outcome = state.outcome
        if (outcome == null) {
            _uiState.update { it.copy(chooseError = true) }
            return
        }
        _uiState.update { it.copy(isSaving = true, saveFailed = false) }
        viewModelScope.launch {
            try {
                vetting.saveDecision(recordId, outcome, state.notes, session.username() ?: UNKNOWN_OFFICER)
                // Queued, not awaited: it waits for a network if there is none, and the save never depends on it.
                syncTrigger.syncVettingDecisionsNow()
                _uiState.update { it.copy(isSaving = false, saved = true) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isSaving = false, saveFailed = true) }
            }
        }
    }

    companion object {
        const val MAX_NOTES_LENGTH = 1000

        /**
         * The officer id when the session does not carry a username. The session no longer restores without one (#70), so
         * this is only a last resort. The backend knows the real officer from the token (#47), so it never stands in for them there.
         */
        const val UNKNOWN_OFFICER = com.example.client.data.local.entity.UNKNOWN_OFFICER
    }
}

@Composable
fun DecisionRoute(onBack: () -> Unit, viewModel: DecisionViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()
    DecisionScreen(
        state, onBack,
        onOutcomeChange = viewModel::onOutcomeChange,
        onNotesChange = viewModel::onNotesChange,
        onSave = viewModel::onSave
    )
}

@Composable
fun DecisionScreen(
    state: DecisionUiState,
    onBack: () -> Unit,
    onOutcomeChange: (DecisionOutcome) -> Unit,
    onNotesChange: (String) -> Unit,
    onSave: () -> Unit
) = CBOCollectorTheme {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SaColors.Surface)
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
            .testTag(DecisionTags.SCREEN),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        ScreenHeader(
            title = stringResource(R.string.vetting_decision_title),
            subtitle = state.recordName,
            onBack = onBack
        )

        if (state.saved) SavedContent(onBack) else FormContent(state, onOutcomeChange, onNotesChange, onSave)
    }
}

@Composable
private fun SavedContent(onDone: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SaColors.TagOkBg)
            .padding(20.dp)
            .testTag(DecisionTags.SAVED)
            .semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(stringResource(R.string.vetting_saved_title), fontFamily = Poppins, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, color = SaColors.TagOkText)
        Text(stringResource(R.string.vetting_saved_body), fontFamily = Figtree, fontSize = 14.sp, lineHeight = 21.sp, color = SaColors.TagOkText)
    }
    FilledPillButton(onClick = onDone, modifier = Modifier.fillMaxWidth().testTag(DecisionTags.DONE)) {
        Text(stringResource(R.string.vetting_back_to_record), fontFamily = Figtree, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = SaColors.Ink)
    }
}

@Composable
private fun FormContent(
    state: DecisionUiState,
    onOutcomeChange: (DecisionOutcome) -> Unit,
    onNotesChange: (String) -> Unit,
    onSave: () -> Unit
) {
    state.previous?.let {
        Text(
            stringResource(R.string.vetting_previous_decision, stringResource(it.labelRes())),
            fontFamily = Figtree, fontSize = 13.sp, lineHeight = 19.sp, color = SaColors.Muted
        )
    }

    Text(stringResource(R.string.vetting_choose_title), fontFamily = Poppins, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = SaColors.Ink)
    Column(modifier = Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        DecisionOutcome.values().forEach { outcome ->
            OutcomeOption(outcome, selected = state.outcome == outcome, onSelect = { onOutcomeChange(outcome) })
        }
    }
    if (state.chooseError) {
        Text(
            stringResource(R.string.vetting_err_choose),
            fontFamily = Figtree, fontSize = 12.5.sp, color = SaColors.Error,
            modifier = Modifier.testTag(DecisionTags.ERROR_CHOOSE)
        )
    }

    Text(stringResource(R.string.vetting_notes_label), fontFamily = Poppins, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = SaColors.Ink)
    SaTextArea(
        value = state.notes,
        onValueChange = onNotesChange,
        placeholder = stringResource(R.string.vetting_notes_placeholder),
        minLines = 4,
        fieldTestTag = DecisionTags.NOTES
    )

    if (state.saveFailed) {
        Text(
            stringResource(R.string.vetting_save_failed),
            fontFamily = Figtree, fontSize = 13.sp, color = SaColors.Error,
            modifier = Modifier.testTag(DecisionTags.SAVE_FAILED)
        )
    }

    FilledPillButton(
        onClick = onSave,
        enabled = !state.isSaving,
        modifier = Modifier.fillMaxWidth().testTag(DecisionTags.SAVE)
    ) {
        Text(
            stringResource(if (state.isSaving) R.string.vetting_saving else R.string.vetting_save_decision),
            fontFamily = Figtree, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = SaColors.Ink
        )
    }
}

@Composable
private fun OutcomeOption(outcome: DecisionOutcome, selected: Boolean, onSelect: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) SaColors.YellowTickBg else SaColors.White, shape)
            .border(if (selected) 2.dp else 1.dp, if (selected) SaColors.YellowDark else SaColors.inkAlpha(0.12f), shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(16.dp)
            .testTag(DecisionTags.option(outcome)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .border(2.dp, if (selected) SaColors.Ink else SaColors.Faint, CircleShape)
                .padding(4.dp)
                .background(if (selected) SaColors.Ink else SaColors.White, CircleShape)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(outcome.optionRes()), fontFamily = Figtree, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = SaColors.Ink)
            Text(stringResource(outcome.hintRes()), fontFamily = Figtree, fontSize = 12.5.sp, lineHeight = 18.sp, color = SaColors.Muted)
        }
    }
}

private fun DecisionOutcome.labelRes() = when (this) {
    DecisionOutcome.APPROVE -> R.string.vetting_outcome_approve
    DecisionOutcome.REJECT -> R.string.vetting_outcome_reject
    DecisionOutcome.FLAG -> R.string.vetting_outcome_flag
}

private fun DecisionOutcome.hintRes() = when (this) {
    DecisionOutcome.APPROVE -> R.string.vetting_hint_approve
    DecisionOutcome.REJECT -> R.string.vetting_hint_reject
    DecisionOutcome.FLAG -> R.string.vetting_hint_flag
}

// "Approve" on the option cards (an action), "Approved" on badges and in sentences (a state).
private fun DecisionOutcome.optionRes() = when (this) {
    DecisionOutcome.APPROVE -> R.string.vetting_option_approve
    DecisionOutcome.REJECT -> R.string.vetting_option_reject
    DecisionOutcome.FLAG -> R.string.vetting_option_flag
}
