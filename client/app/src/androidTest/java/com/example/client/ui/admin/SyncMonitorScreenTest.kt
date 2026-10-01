package com.example.client.ui.admin

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
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

/** The Admin sync monitor, on a device. */
@RunWith(AndroidJUnit4::class)
class SyncMonitorScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun text(id: Int, vararg args: Any) = composeRule.activity.getString(id, *args)

    private fun show(state: SyncMonitorUiState, onRefresh: () -> Unit = {}, onBack: () -> Unit = {}) =
        composeRule.setContent { SyncMonitorScreen(state, onRefresh = onRefresh, onBack = onBack) }

    private fun row(card: String, row: SyncRow) = composeRule.onNodeWithTag(SyncMonitorTags.row(card, row)).performScrollTo()

    @Test
    fun withCounts_eachFormShowsItsOwnStates() {
        show(SyncMonitorUiState(loading = false, snapshot = sampleSyncSnapshot()))

        composeRule.onNodeWithTag(SyncMonitorTags.FORM1).assertIsDisplayed()
        row(SyncMonitorTags.FORM1, SyncRow.WAITING).assertTextContains("2")
        row(SyncMonitorTags.FORM1, SyncRow.RETRYING).assertTextContains("1")
        row(SyncMonitorTags.FORM1, SyncRow.NEEDS_ATTENTION).assertTextContains("3")
        row(SyncMonitorTags.FORM1, SyncRow.FORWARDED).assertTextContains("10")
        row(SyncMonitorTags.FORM1, SyncRow.DUPLICATES).assertTextContains("4")

        composeRule.onNodeWithTag(SyncMonitorTags.FORM2).performScrollTo().assertIsDisplayed()
        row(SyncMonitorTags.FORM2, SyncRow.WAITING).assertTextContains("1")
        row(SyncMonitorTags.FORM2, SyncRow.FORWARDED).assertTextContains("7")
        row(SyncMonitorTags.FORM2, SyncRow.SUPERSEDED).assertTextContains("2")
    }

    @Test
    fun form1HasNoSupersededRow_andForm2HasNoDuplicatesRow() {
        show(SyncMonitorUiState(loading = false, snapshot = sampleSyncSnapshot()))

        composeRule.onNodeWithTag(SyncMonitorTags.row(SyncMonitorTags.FORM1, SyncRow.SUPERSEDED)).assertDoesNotExist()
        composeRule.onNodeWithTag(SyncMonitorTags.row(SyncMonitorTags.FORM2, SyncRow.DUPLICATES)).assertDoesNotExist()
    }

    @Test
    fun theTotals_areShown() {
        show(SyncMonitorUiState(loading = false, snapshot = sampleSyncSnapshot()))

        composeRule.onNodeWithTag(SyncMonitorTags.total(SyncMonitorTags.FORM1)).assertTextContains(text(R.string.admin_sync_total, 20))
        composeRule.onNodeWithTag(SyncMonitorTags.total(SyncMonitorTags.FORM2)).performScrollTo().assertTextContains(text(R.string.admin_sync_total, 10))
    }

    @Test
    fun anEmptyServer_showsZeroEverywhere_notAnError() {
        show(SyncMonitorUiState(loading = false, snapshot = sampleSyncSnapshot(sampleCounts(), sampleCounts())))

        row(SyncMonitorTags.FORM1, SyncRow.NEEDS_ATTENTION).assertTextContains("0")
        row(SyncMonitorTags.FORM2, SyncRow.NEEDS_ATTENTION).assertTextContains("0")
        composeRule.onNodeWithTag(SyncMonitorTags.NOTICE).assertDoesNotExist()
    }

    @Test
    fun beforeTheFirstAnswer_itSaysItIsLoading_andOffersNoCounts() {
        show(SyncMonitorUiState(loading = true))

        composeRule.onNodeWithTag(SyncMonitorTags.LOADING).assertIsDisplayed()
        composeRule.onNodeWithTag(SyncMonitorTags.FORM1).assertDoesNotExist()
        composeRule.onNodeWithTag(SyncMonitorTags.REFRESH).assertIsNotEnabled()
    }

    @Test
    fun offlineWithNothingLoaded_explainsWhy_andRefreshIsStillAvailable() {
        show(SyncMonitorUiState(loading = false, snapshot = null, notice = AdminNotice.OFFLINE))

        composeRule.onNodeWithTag(SyncMonitorTags.NOTICE).assertTextContains(text(R.string.admin_sync_notice_offline))
        composeRule.onNodeWithTag(SyncMonitorTags.REFRESH).assertIsEnabled()
    }

    @Test
    fun aFailedRefresh_keepsTheEarlierCountsOnScreen() {
        show(SyncMonitorUiState(loading = false, snapshot = sampleSyncSnapshot(), notice = AdminNotice.FAILED))

        composeRule.onNodeWithTag(SyncMonitorTags.NOTICE).assertTextContains(text(R.string.admin_sync_notice_failed_kept))
        row(SyncMonitorTags.FORM1, SyncRow.FORWARDED).assertTextContains("10")
    }

    @Test
    fun refresh_callsItsHandler() {
        var refreshed = 0
        show(SyncMonitorUiState(loading = false, snapshot = sampleSyncSnapshot()), onRefresh = { refreshed++ })

        composeRule.onNodeWithTag(SyncMonitorTags.REFRESH).performClick()

        assertEquals(1, refreshed)
    }
}
