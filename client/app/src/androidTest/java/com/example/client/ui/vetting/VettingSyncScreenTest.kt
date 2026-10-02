package com.example.client.ui.vetting

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.client.R
import com.example.client.data.local.entity.DecisionOutcome
import com.example.client.ui.components.SyncTabTags
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The Vetting Sync tab: the officer's decisions that are not on the server yet, and the way out. */
@RunWith(AndroidJUnit4::class)
class VettingSyncScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun text(id: Int, vararg args: Any) = composeRule.activity.getString(id, *args)

    private fun item(id: String, sync: DecisionSyncDisplay, outcome: DecisionOutcome = DecisionOutcome.APPROVE) =
        DecisionQueueItem(id, "Record $id", outcome, 1_700_000_000_000L, sync)

    @Test
    fun withNothingWaiting_saysEverythingIsOnTheServer() {
        composeRule.setContent { VettingSyncScreen(VettingSyncUiState(listOf(item("a", DecisionSyncDisplay.SYNCED))), {}, {}) }

        composeRule.onNodeWithText(text(R.string.sync_all_clear)).assertIsDisplayed()
        composeRule.onNodeWithTag(VettingSyncTags.item("a")).assertDoesNotExist()
    }

    @Test
    fun listsTheUnsentDecisions_withTheirOutcomeAndExplanation() {
        val state = VettingSyncUiState(
            listOf(
                item("p", DecisionSyncDisplay.PENDING, DecisionOutcome.FLAG),
                item("f", DecisionSyncDisplay.FAILED_FINAL, DecisionOutcome.REJECT),
                item("s", DecisionSyncDisplay.SYNCED)
            )
        )
        composeRule.setContent { VettingSyncScreen(state, {}, {}) }

        composeRule.onNodeWithTag(VettingSyncTags.item("p")).performScrollTo().assertIsDisplayed()
            .assertTextContains(text(R.string.vetting_outcome_flag), substring = true)
        composeRule.onNodeWithTag(VettingSyncTags.item("p")).assertTextContains(text(R.string.vetting_sync_pending), substring = true)
        composeRule.onNodeWithTag(VettingSyncTags.item("f")).performScrollTo()
            .assertTextContains(text(R.string.vetting_sync_final), substring = true)
        composeRule.onNodeWithTag(VettingSyncTags.item("s")).assertDoesNotExist()
        composeRule.onNodeWithText(text(R.string.vetting_sync_panel_failed, 1)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun signOut_isOfferedAndWorks() {
        var signedOut = 0
        composeRule.setContent { VettingSyncScreen(VettingSyncUiState(), {}, { signedOut++ }) }

        composeRule.onNodeWithTag(SyncTabTags.SIGN_OUT).performScrollTo().performClick()

        assertEquals(1, signedOut)
    }
}
