package com.example.client.ui.vetting

import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.client.R
import com.example.client.auth.SessionManager
import com.example.client.auth.UserRole
import com.example.client.data.local.entity.DecisionOutcome
import com.example.client.data.local.entity.SyncStatus
import com.example.client.testing.FakeVettingRecordsRepository
import com.example.client.testing.FakeVettingRepository
import com.example.client.testing.FakeVettingSyncTrigger
import com.example.client.testing.InMemoryTokenStorage
import com.example.client.testing.sampleDecision
import com.example.client.testing.sampleRecord
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Whether each decision has reached the server is visible on the record and in the list, and saving asks for a sync. */
@RunWith(AndroidJUnit4::class)
class DecisionSyncUiTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun text(id: Int) = composeRule.activity.getString(id)

    private fun decision(status: SyncStatus, retryCount: Int = 0, code: String? = null) =
        sampleDecision(outcome = DecisionOutcome.APPROVE).copy(syncStatus = status, retryCount = retryCount, syncErrorCode = code)

    private fun showDetail(decision: com.example.client.data.local.entity.VettingDecision?) {
        composeRule.setContent { BeneficiaryDetailScreen(DetailUiState.Ready(sampleRecord(), decision), {}, {}) }
    }

    // ── on the record ──────────────────────────────────────────────────────────────

    @Test
    fun aDecisionWaitingToSend_saysSo() {
        showDetail(decision(SyncStatus.PENDING))

        composeRule.onNodeWithTag(DetailTags.CURRENT_DECISION).assertTextContains(text(R.string.vetting_sync_pending))
    }

    @Test
    fun aSentDecision_saysSo() {
        showDetail(decision(SyncStatus.SYNCED))

        composeRule.onNodeWithTag(DetailTags.CURRENT_DECISION).assertTextContains(text(R.string.vetting_sync_synced))
    }

    @Test
    fun everyKindOfSyncFailure_isExplainedDifferently() {
        val cases = listOf(
            decision(SyncStatus.FAILED, retryCount = 1) to R.string.vetting_sync_retry,
            decision(SyncStatus.FAILED, retryCount = 5, code = "SERVER_ERROR") to R.string.vetting_sync_final,
            decision(SyncStatus.FAILED, retryCount = 5, code = "VALIDATION_FAILED") to R.string.vetting_sync_rejected
        )
        var current by mutableStateOf(cases.first().first)
        composeRule.setContent { BeneficiaryDetailScreen(DetailUiState.Ready(sampleRecord(), current), {}, {}) }

        cases.forEach { (decision, expected) ->
            composeRule.runOnUiThread { current = decision }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag(DetailTags.CURRENT_DECISION).assertTextContains(text(expected))
        }
    }

    @Test
    fun withoutADecision_thereIsNothingToSend_soNoSyncLineIsShown() {
        showDetail(null)

        composeRule.onAllNodesWithText(text(R.string.vetting_sync_pending), substring = true).assertCountEquals(0)
    }

    // ── in the list ────────────────────────────────────────────────────────────────

    private fun item(id: String, name: String, outcome: DecisionOutcome? = null, sync: DecisionSyncDisplay? = null) =
        RecordItem(id = id, legalName = name, province = "Gauteng", contactName = "Nomsa", decision = outcome, decisionSync = sync)

    @Test
    fun aDecisionNotYetSent_saysSoOnItsRow_andASentOneStaysQuiet() {
        val state = VettingListUiState(
            loaded = true,
            items = listOf(
                item("a", "Alpha", DecisionOutcome.APPROVE, DecisionSyncDisplay.PENDING),
                item("b", "Beta", DecisionOutcome.REJECT, DecisionSyncDisplay.SYNCED),
                item("c", "Gamma", DecisionOutcome.FLAG, DecisionSyncDisplay.FAILED_FINAL),
                item("d", "Delta")
            )
        )
        composeRule.setContent { VettingListScreen(state, {}, {}) }

        composeRule.onNodeWithTag(ListTags.item("a")).assertTextContains(text(R.string.vetting_sync_pending), substring = true)
        composeRule.onNodeWithTag(ListTags.item("c")).assertTextContains(text(R.string.vetting_sync_final), substring = true)
        composeRule.onAllNodesWithText(text(R.string.vetting_sync_synced), substring = true).assertCountEquals(0)
        composeRule.onNodeWithTag(ListTags.item("d")).assertTextContains(text(R.string.vetting_decision_none), substring = true)
    }

    // ── saving asks for a sync ─────────────────────────────────────────────────────

    @Test
    fun savingADecision_asksForASyncStraightAway() {
        val records = FakeVettingRecordsRepository(listOf(sampleRecord("a")))
        val vetting = FakeVettingRepository()
        val trigger = FakeVettingSyncTrigger()
        val session = SessionManager(InMemoryTokenStorage()).apply { startSession("jwt", UserRole.VETTING, null, "vetting_test_user") }
        composeRule.setContent {
            val vm = remember { DecisionViewModel(SavedStateHandle(mapOf(VETTING_RECORD_ARG to "a")), records, vetting, session, trigger) }
            val state by vm.uiState.collectAsState()
            DecisionScreen(state, {}, vm::onOutcomeChange, vm::onNotesChange, vm::onSave)
        }
        composeRule.onNodeWithTag(DecisionTags.option(DecisionOutcome.APPROVE)).performClick()

        composeRule.onNodeWithTag(DecisionTags.SAVE).performScrollTo().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(DecisionTags.SAVED).assertIsDisplayed()
        assertEquals(1, trigger.calls)
    }
}
