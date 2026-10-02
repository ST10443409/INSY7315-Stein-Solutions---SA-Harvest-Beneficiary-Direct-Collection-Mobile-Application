package com.example.client.ui.cbo

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
import com.example.client.ui.components.SyncTabTags
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The collector's Home and Sync tabs, driven with the real stored values they would be shown. */
@RunWith(AndroidJUnit4::class)
class CboTabsTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun text(id: Int, vararg args: Any) = composeRule.activity.getString(id, *args)

    private fun item(id: String, display: SubmissionDisplay, createdAt: Long = System.currentTimeMillis(), signatures: Int = 2, photos: Int = 1) =
        SubmissionItem(id = id, donorName = "Donor $id", createdAt = createdAt, display = display, signatureCount = signatures, photoCount = photos)

    // ── Home ────────────────────────────────────────────────────────────────────────

    @Test
    fun home_greetsTheSignedInUser_byTheirUsername() {
        composeRule.setContent { CboHomeScreen(CboHomeUiState(username = "sipho"), {}, {}, {}) }

        composeRule.onNodeWithTag(CboHomeTags.SCREEN).assertIsDisplayed()
        composeRule.onNodeWithText(text(R.string.greeting_hello, "Sipho")).assertIsDisplayed()
        composeRule.onNodeWithText(text(R.string.role_cbo_collection)).assertIsDisplayed()
    }

    @Test
    fun home_withNothingToday_saysSo_ratherThanListingSampleStops() {
        composeRule.setContent { CboHomeScreen(CboHomeUiState(username = "sipho"), {}, {}, {}) }

        composeRule.onNodeWithTag(CboHomeTags.TODAY_EMPTY).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(text(R.string.home_today, 0), ignoreCase = true).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun home_listsOnlyTodaysCollections() {
        val aWeekAgo = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
        val state = CboHomeUiState(
            username = "sipho",
            submissions = SubmissionsUiState(
                listOf(item("today1", SubmissionDisplay.PENDING), item("today2", SubmissionDisplay.SYNCED), item("old", SubmissionDisplay.SYNCED, aWeekAgo))
            )
        )
        composeRule.setContent { CboHomeScreen(state, {}, {}, {}) }

        composeRule.onNodeWithTag(CboHomeTags.today("today1")).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(CboHomeTags.today("today2")).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(CboHomeTags.today("old")).assertDoesNotExist()
        composeRule.onNodeWithText(text(R.string.home_today, 2), ignoreCase = true).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun home_theTwoCards_openTheCollectionAndTheHistory() {
        var started = 0
        var history = 0
        composeRule.setContent { CboHomeScreen(CboHomeUiState(), { started++ }, { history++ }, {}) }

        composeRule.onNodeWithTag(CboHomeTags.START).performClick()
        composeRule.onNodeWithTag(CboHomeTags.PAST).performClick()

        assertEquals(1, started)
        assertEquals(1, history)
    }

    @Test
    fun home_afailedSubmission_showsUpOnTheStatusStrip_notOnlyOnTheSyncTab() {
        val state = CboHomeUiState(submissions = SubmissionsUiState(listOf(item("f", SubmissionDisplay.FAILED_FINAL))))
        composeRule.setContent { CboHomeScreen(state, {}, {}, {}) }

        composeRule.onNodeWithText(text(R.string.status_failed_count, 1)).assertIsDisplayed()
    }

    // ── Sync ────────────────────────────────────────────────────────────────────────

    @Test
    fun sync_withNothingWaiting_saysEverythingIsOnTheServer_andHasNothingToSend() {
        composeRule.setContent { CboSyncScreen(SubmissionsUiState(listOf(item("a", SubmissionDisplay.SYNCED))), {}, {}) }

        composeRule.onNodeWithText(text(R.string.sync_all_clear)).assertIsDisplayed()
        composeRule.onNodeWithTag(SyncTabTags.SYNC_NOW).performScrollTo().assertTextContains(text(R.string.sync_all_synced))
        composeRule.onNodeWithTag(SyncTabTags.FAILED).assertDoesNotExist()
        composeRule.onNodeWithTag(SubmissionsTags.item("a")).assertDoesNotExist() // a synced record is not in the queue
    }

    @Test
    fun sync_listsWhatIsWaiting_withTheCollectorsExistingExplanations() {
        val state = SubmissionsUiState(
            listOf(
                item("pending", SubmissionDisplay.PENDING),
                item("dup", SubmissionDisplay.FAILED_DUPLICATE),
                item("done", SubmissionDisplay.SYNCED)
            )
        )
        composeRule.setContent { CboSyncScreen(state, {}, {}) }

        composeRule.onNodeWithText(text(R.string.sync_waiting, 2)).assertIsDisplayed()
        composeRule.onNodeWithTag(SubmissionsTags.item("pending")).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(SubmissionsTags.item("dup")).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(SubmissionsTags.item("done")).assertDoesNotExist()
        composeRule.onNodeWithText(text(R.string.submissions_panel_failed, 1)).performScrollTo().assertIsDisplayed()
    }

    // #70: unsent work stays on the phone but is only sent by whoever captured it, so signing out says so first.
    @Test
    fun sync_signOutWithUnsentWork_asksFirst_andOnlySignsOutWhenConfirmed() {
        var signedOut = 0
        val state = SubmissionsUiState(listOf(item("a", SubmissionDisplay.PENDING), item("b", SubmissionDisplay.PENDING)))
        composeRule.setContent { CboSyncScreen(state, {}, { signedOut++ }) }

        composeRule.onNodeWithTag(SyncTabTags.SIGN_OUT).performScrollTo().performClick()

        composeRule.onNodeWithTag(SyncTabTags.SIGN_OUT_DIALOG).assertIsDisplayed()
        assertEquals(0, signedOut) // nothing happened yet
        composeRule.onNodeWithTag(SyncTabTags.SIGN_OUT_STAY).performClick()
        composeRule.onNodeWithTag(SyncTabTags.SIGN_OUT_DIALOG).assertDoesNotExist()
        assertEquals(0, signedOut)

        composeRule.onNodeWithTag(SyncTabTags.SIGN_OUT).performScrollTo().performClick()
        composeRule.onNodeWithTag(SyncTabTags.SIGN_OUT_ANYWAY).performClick()
        assertEquals(1, signedOut)
    }

    @Test
    fun sync_picturesStillToUpload_alsoCountAsUnsent() {
        var signedOut = 0
        val state = SubmissionsUiState(listOf(item("a", SubmissionDisplay.SYNCED)), attachmentsWaiting = 2)
        composeRule.setContent { CboSyncScreen(state, {}, { signedOut++ }) }

        composeRule.onNodeWithTag(SyncTabTags.SIGN_OUT).performScrollTo().performClick()

        composeRule.onNodeWithTag(SyncTabTags.SIGN_OUT_DIALOG).assertIsDisplayed()
        assertEquals(0, signedOut)
    }

    @Test
    fun sync_otherAccountsWork_isMentioned() {
        composeRule.setContent { CboSyncScreen(SubmissionsUiState(otherAccountsWaiting = 3), {}, {}) }

        composeRule.onNodeWithTag(SyncTabTags.OTHER_ACCOUNTS).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun sync_signOut_isOfferedAndWorks() {
        var signedOut = 0
        composeRule.setContent { CboSyncScreen(SubmissionsUiState(), {}, { signedOut++ }) }

        composeRule.onNodeWithTag(SyncTabTags.SIGN_OUT).performScrollTo().performClick()

        assertEquals(1, signedOut)
    }

    @Test
    fun sync_theQueueShowsHowManySignaturesAndPhotosAreWaitingWithEachCollection() {
        composeRule.setContent {
            CboSyncScreen(SubmissionsUiState(listOf(item("a", SubmissionDisplay.PENDING, signatures = 2, photos = 3))), {}, {})
        }

        composeRule.onNodeWithTag(SubmissionsTags.item("a")).performScrollTo()
            .assertTextContains(text(R.string.sync_row_attachments, 2, 3), substring = true)
    }
}
