package com.example.client.ui.admin

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
import com.example.client.testing.sampleCounts
import com.example.client.testing.sampleSyncSnapshot
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The Admin Overview, on a device: what it says from the server's counts, and where each card leads. */
@RunWith(AndroidJUnit4::class)
class AdminShellTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun text(id: Int, vararg args: Any) = composeRule.activity.getString(id, *args)

    private fun show(state: AdminOverviewUiState, onOpen: (AdminDestination) -> Unit = {}) {
        composeRule.setContent { AdminOverviewScreen(state, onOpen) }
    }

    private fun loaded(form1: com.example.client.data.repository.FormSyncCounts, form2: com.example.client.data.repository.FormSyncCounts) =
        AdminOverviewUiState(username = "admin_user", loading = false, snapshot = sampleSyncSnapshot(form1, form2))

    @Test
    fun theOverview_showsEveryOversightSection_withItsTitleAndDescription() {
        show(AdminOverviewUiState())

        AdminDestination.values().forEach { destination ->
            val entry = composeRule.onNodeWithTag(AdminTags.entry(destination)).performScrollTo()
            entry.assertIsDisplayed()
            entry.assertTextContains(text(destination.title))
            entry.assertTextContains(text(destination.description))
        }
    }

    @Test
    fun tappingAnEntry_opensThatDestination() {
        val opened = mutableListOf<AdminDestination>()
        show(AdminOverviewUiState()) { opened += it }

        AdminDestination.values().forEach { composeRule.onNodeWithTag(AdminTags.entry(it)).performScrollTo().performClick() }

        assertEquals(AdminDestination.values().toList(), opened)
    }

    @Test
    fun theOverview_greetsTheAdminByUsername_inTheDemosLayout() {
        show(AdminOverviewUiState(username = "admin_user"))

        composeRule.onNodeWithText(text(R.string.admin_overview_title)).assertIsDisplayed()
        composeRule.onNodeWithText("Admin_user", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText(text(R.string.role_admin), substring = true).assertIsDisplayed()
    }

    @Test
    fun whenRecordsNeedAttention_theHeroSaysHowMany_andOpensTheFailedSyncs() {
        val opened = mutableListOf<AdminDestination>()
        show(loaded(sampleCounts(needsAttention = 3), sampleCounts(needsAttention = 2))) { opened += it }

        composeRule.onNodeWithTag(AdminTags.HERO).assertTextContains(text(R.string.admin_overview_hero_attention, 5), substring = true)
        composeRule.onNodeWithTag(AdminTags.HERO).performClick()

        assertEquals(listOf(AdminDestination.FAILED_SYNC), opened)
    }

    @Test
    fun whenNothingNeedsAttention_theHeroSaysSo_andOpensTheMonitor() {
        val opened = mutableListOf<AdminDestination>()
        show(loaded(sampleCounts(forwarded = 9), sampleCounts(forwarded = 4))) { opened += it }

        composeRule.onNodeWithTag(AdminTags.HERO).assertTextContains(text(R.string.admin_overview_hero_clear), substring = true)
        composeRule.onNodeWithTag(AdminTags.NOTHING_TO_FLAG).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(AdminTags.HERO).performClick()

        assertEquals(listOf(AdminDestination.SYNC_MONITOR), opened)
    }

    @Test
    fun theServersCounts_becomeAlerts_eachOpeningItsOwnSection() {
        val opened = mutableListOf<AdminDestination>()
        show(loaded(sampleCounts(waiting = 2, needsAttention = 3, duplicates = 1), sampleCounts(needsAttention = 4))) { opened += it }

        AdminAlertKind.values().forEach { composeRule.onNodeWithTag(AdminTags.alert(it)).performScrollTo().assertIsDisplayed() }
        composeRule.onNodeWithTag(AdminTags.alert(AdminAlertKind.COLLECTIONS_NEED_ATTENTION))
            .assertTextContains(text(R.string.admin_alert_collections, 3), substring = true)
        composeRule.onNodeWithTag(AdminTags.alert(AdminAlertKind.WAITING_TO_SYNC)).performClick()

        assertEquals(listOf(AdminDestination.SYNC_MONITOR), opened)
    }

    @Test
    fun theTotalsTiles_showTheServersTotals_notInventedFigures() {
        show(loaded(sampleCounts(forwarded = 12), sampleCounts(forwarded = 7)))

        composeRule.onNodeWithText("12").assertIsDisplayed()
        composeRule.onNodeWithText("7").assertIsDisplayed()
    }

    @Test
    fun beforeTheServerAnswers_noCountsAreShown() {
        show(AdminOverviewUiState(loading = true))

        composeRule.onNodeWithTag(AdminTags.HERO).assertTextContains(text(R.string.admin_overview_hero_loading), substring = true)
        composeRule.onNodeWithTag(AdminTags.NOTHING_TO_FLAG).performScrollTo().assertTextContains(text(R.string.admin_overview_alerts_unknown))
    }
}
