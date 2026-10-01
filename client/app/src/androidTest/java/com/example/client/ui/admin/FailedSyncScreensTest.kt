package com.example.client.ui.admin

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.client.R
import com.example.client.data.repository.AdminActionEntry
import com.example.client.data.repository.DuplicateOf
import com.example.client.data.repository.SyncForm
import com.example.client.data.repository.SyncState
import com.example.client.testing.SAMPLE_ERROR
import com.example.client.testing.sampleAttention
import com.example.client.testing.sampleDetail
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The failed-sync list and record screens (#50), on a device. */
@RunWith(AndroidJUnit4::class)
class FailedSyncScreensTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun text(id: Int, vararg args: Any) = composeRule.activity.getString(id, *args)

    // ── the list ───────────────────────────────────────────────────────────────────

    private fun showList(
        state: FailedSyncListUiState,
        onRefresh: () -> Unit = {},
        onOpen: (SyncForm, String) -> Unit = { _, _ -> }
    ) = composeRule.setContent { FailedSyncListScreen(state, onRefresh = onRefresh, onBack = {}, onOpen = onOpen) }

    @Test
    fun theList_showsEachRecord_withItsErrorKindAndAttempts() {
        showList(
            FailedSyncListUiState(
                loading = false, loaded = true,
                items = listOf(
                    sampleAttention("c1", attempts = 3),
                    sampleAttention("d1", SyncForm.VETTING_DECISION, label = "APPROVE · beneficiary fs-1", error = "Foodspace answered 404 (Not Found)", attempts = 1)
                )
            )
        )

        composeRule.onNodeWithTag(FailedSyncTags.item("c1")).assertIsDisplayed()
            .assertTextContains(SAMPLE_ERROR, substring = true)
            .assertTextContains(text(R.string.admin_failed_form1), substring = true)
            .assertTextContains(text(R.string.admin_failed_attempts, 3), substring = true)
            .assertTextContains(text(R.string.admin_failed_state_label_needs_attention), substring = true)
        composeRule.onNodeWithTag(FailedSyncTags.item("d1")).assertIsDisplayed()
            .assertTextContains("Foodspace answered 404 (Not Found)", substring = true)
            .assertTextContains(text(R.string.admin_failed_form2), substring = true)
            .assertTextContains(text(R.string.admin_failed_attempts_one), substring = true)
    }

    @Test
    fun aHeldDuplicate_isLabelledAsOne_andNeedsNoErrorText() {
        showList(
            FailedSyncListUiState(
                loading = false, loaded = true,
                items = listOf(sampleAttention("dup", state = SyncState.DUPLICATE_HELD, error = null, attempts = 0))
            )
        )

        composeRule.onNodeWithTag(FailedSyncTags.item("dup"))
            .assertTextContains(text(R.string.admin_failed_state_label_duplicate_held), substring = true)
            .assertTextContains(text(R.string.admin_failed_no_error), substring = true)
    }

    @Test
    fun tappingARecord_opensItByKindAndId() {
        val opened = mutableListOf<Pair<SyncForm, String>>()
        showList(
            FailedSyncListUiState(loading = false, loaded = true, items = listOf(sampleAttention("d1", SyncForm.VETTING_DECISION))),
            onOpen = { form, id -> opened += form to id }
        )

        composeRule.onNodeWithTag(FailedSyncTags.item("d1")).performClick()

        assertEquals(listOf(SyncForm.VETTING_DECISION to "d1"), opened)
    }

    @Test
    fun nothingNeedingAttention_isSaidPlainly_notShownAsAnError() {
        showList(FailedSyncListUiState(loading = false, loaded = true))

        composeRule.onNodeWithTag(FailedSyncTags.EMPTY).assertIsDisplayed()
            .assertTextContains(text(R.string.admin_failed_empty_title), substring = true)
        composeRule.onNodeWithTag(FailedSyncTags.NOTICE).assertDoesNotExist()
    }

    @Test
    fun beforeTheFirstAnswer_itSaysItIsLoading_notThatNothingNeedsAttention() {
        showList(FailedSyncListUiState(loading = true))

        composeRule.onNodeWithTag(FailedSyncTags.LOADING).assertIsDisplayed()
        composeRule.onNodeWithTag(FailedSyncTags.EMPTY).assertDoesNotExist()
        composeRule.onNodeWithTag(FailedSyncTags.REFRESH).assertIsNotEnabled()
    }

    @Test
    fun offlineBeforeAnyAnswer_explainsWhy_andNeverClaimsTheListIsEmpty() {
        showList(FailedSyncListUiState(loading = false, loaded = false, notice = AdminNotice.OFFLINE))

        composeRule.onNodeWithTag(FailedSyncTags.NOTICE).assertTextContains(text(R.string.admin_failed_notice_offline))
        composeRule.onNodeWithTag(FailedSyncTags.EMPTY).assertDoesNotExist()
        composeRule.onNodeWithTag(FailedSyncTags.REFRESH).assertIsEnabled()
    }

    @Test
    fun aFailedRefresh_keepsTheEarlierRecordsOnScreen() {
        showList(FailedSyncListUiState(loading = false, loaded = true, items = listOf(sampleAttention("c1")), notice = AdminNotice.FAILED))

        composeRule.onNodeWithTag(FailedSyncTags.NOTICE).assertTextContains(text(R.string.admin_failed_notice_failed_kept))
        composeRule.onNodeWithTag(FailedSyncTags.item("c1")).assertIsDisplayed()
    }

    @Test
    fun refresh_callsItsHandler() {
        var refreshed = 0
        showList(FailedSyncListUiState(loading = false, loaded = true), onRefresh = { refreshed++ })

        composeRule.onNodeWithTag(FailedSyncTags.REFRESH).performClick()

        assertEquals(1, refreshed)
    }

    // ── one record ─────────────────────────────────────────────────────────────────

    private class Taps {
        var retry = 0
        var startDismiss = 0
        var cancelDismiss = 0
        var confirmDismiss = 0
        var reason = ""
    }

    private fun showDetail(state: FailedSyncDetailUiState, taps: Taps = Taps()) = composeRule.setContent {
        FailedSyncDetailScreen(
            state, onBack = {}, onRetry = { taps.retry++ }, onStartDismiss = { taps.startDismiss++ },
            onCancelDismiss = { taps.cancelDismiss++ }, onReasonChange = { taps.reason = it }, onConfirmDismiss = { taps.confirmDismiss++ }
        )
    }

    @Test
    fun theError_isShownBeforeAnythingIsDone() {
        showDetail(FailedSyncDetailUiState(loading = false, detail = sampleDetail(attempts = 4)))

        composeRule.onNodeWithTag(FailedSyncDetailTags.ERROR).assertIsDisplayed().assertTextContains(SAMPLE_ERROR)
        composeRule.onNodeWithTag(FailedSyncDetailTags.STATE).assertTextContains(text(R.string.admin_failed_state_label_needs_attention))
        composeRule.onNodeWithTag(FailedSyncDetailTags.OUTCOME).assertDoesNotExist()
    }

    @Test
    fun retryAndDismiss_areOffered_forARecordThatNeedsAttention_andRetryCallsItsHandler() {
        val taps = Taps()
        showDetail(FailedSyncDetailUiState(loading = false, detail = sampleDetail()), taps)

        composeRule.onNodeWithTag(FailedSyncDetailTags.RETRY).performScrollTo().assertIsEnabled()
            .assertTextContains(text(R.string.admin_failed_retry)).performClick()
        composeRule.onNodeWithTag(FailedSyncDetailTags.DISMISS).performScrollTo().assertIsDisplayed()

        assertEquals(1, taps.retry)
    }

    @Test
    fun whileBusy_retryIsDisabled_andSaysItIsRetrying() {
        showDetail(FailedSyncDetailUiState(loading = false, detail = sampleDetail(), busy = true))

        composeRule.onNodeWithTag(FailedSyncDetailTags.RETRY).performScrollTo().assertIsNotEnabled()
            .assertTextContains(text(R.string.admin_failed_retry_busy))
    }

    @Test
    fun aHeldDuplicate_showsWhatItLooksLike_andOffersToReleaseIt() {
        val original = DuplicateOf("orig", "Donor orig · delivery note DN-1", SyncState.FORWARDED, 1_700_000_000_000L)
        showDetail(
            FailedSyncDetailUiState(
                loading = false,
                detail = sampleDetail("dup", state = SyncState.DUPLICATE_HELD, error = null, attempts = 0, duplicateOf = original)
            )
        )

        composeRule.onNodeWithTag(FailedSyncDetailTags.DUPLICATE).performScrollTo().assertIsDisplayed()
            .assertTextContains("Donor orig", substring = true)
            .assertTextContains(text(R.string.admin_failed_state_label_forwarded), substring = true)
        composeRule.onNodeWithTag(FailedSyncDetailTags.RETRY).performScrollTo().assertTextContains(text(R.string.admin_failed_release))
        composeRule.onNodeWithTag(FailedSyncDetailTags.ERROR).assertTextContains(text(R.string.admin_failed_no_error))
    }

    @Test
    fun aRecordNothingCanBeDoneWith_offersNoButtons_andSaysWhy() {
        showDetail(FailedSyncDetailUiState(loading = false, detail = sampleDetail(state = SyncState.FORWARDED, error = null)))

        composeRule.onNodeWithTag(FailedSyncDetailTags.NO_ACTIONS).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(FailedSyncDetailTags.RETRY).assertDoesNotExist()
        composeRule.onNodeWithTag(FailedSyncDetailTags.DISMISS).assertDoesNotExist()
    }

    @Test
    fun aRecordStillRetrying_canBeRetriedNow_butNotDismissed() {
        showDetail(FailedSyncDetailUiState(loading = false, detail = sampleDetail(state = SyncState.RETRYING, nextAttemptMillis = 1_700_000_300_000L)))

        composeRule.onNodeWithTag(FailedSyncDetailTags.RETRY).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(FailedSyncDetailTags.DISMISS).assertDoesNotExist()
    }

    @Test
    fun dismissing_asksForAReason_andCannotBeConfirmedWithoutOne() {
        val taps = Taps()
        composeRule.setContent {
            // Stateful, like the real screen: what is typed comes back in as the reason.
            var state by remember { mutableStateOf(FailedSyncDetailUiState(loading = false, detail = sampleDetail(), dismissing = true)) }
            FailedSyncDetailScreen(
                state, onBack = {}, onRetry = {}, onStartDismiss = {}, onCancelDismiss = { taps.cancelDismiss++ },
                onReasonChange = { state = state.copy(reason = it); taps.reason = it }, onConfirmDismiss = { taps.confirmDismiss++ }
            )
        }

        composeRule.onNodeWithTag(FailedSyncDetailTags.DISMISS_CONFIRM).performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag(FailedSyncDetailTags.REASON).performScrollTo().assertIsDisplayed().performTextInput("Handled in Foodspace")
        composeRule.onNodeWithTag(FailedSyncDetailTags.DISMISS_CONFIRM).performScrollTo().assertIsEnabled()
        composeRule.onNodeWithTag(FailedSyncDetailTags.DISMISS_CANCEL).performScrollTo().performClick()

        assertEquals("Handled in Foodspace", taps.reason)
        assertEquals(1, taps.cancelDismiss)
        assertEquals(0, taps.confirmDismiss)
    }

    @Test
    fun withAReason_dismissCanBeConfirmed() {
        val taps = Taps()
        showDetail(FailedSyncDetailUiState(loading = false, detail = sampleDetail(), dismissing = true, reason = "Already in Foodspace"), taps)

        composeRule.onNodeWithTag(FailedSyncDetailTags.DISMISS_CONFIRM).performScrollTo().assertIsEnabled().performClick()

        assertEquals(1, taps.confirmDismiss)
    }

    @Test
    fun theOutcomeOfARetry_isShownAtTheTop() {
        showDetail(FailedSyncDetailUiState(loading = false, detail = sampleDetail(state = SyncState.FORWARDED, error = null), outcome = ActionOutcome.SENT))

        composeRule.onNodeWithTag(FailedSyncDetailTags.OUTCOME).assertIsDisplayed().assertTextContains(text(R.string.admin_failed_outcome_sent))
    }

    @Test
    fun theServersOwnWords_areShownForAConflict() {
        showDetail(
            FailedSyncDetailUiState(
                loading = false, detail = sampleDetail(state = SyncState.FORWARDED, error = null),
                notice = DetailNotice.CONFLICT, serverMessage = "Foodspace already has this record."
            )
        )

        composeRule.onNodeWithTag(FailedSyncDetailTags.NOTICE).assertTextContains("Foodspace already has this record.")
    }

    @Test
    fun whatHasBeenDone_isListedWithWhoAndWhy() {
        showDetail(
            FailedSyncDetailUiState(
                loading = false,
                detail = sampleDetail(
                    state = SyncState.DISMISSED, canRetry = true, canDismiss = false,
                    history = listOf(AdminActionEntry(1_700_000_100_000L, "admin_test_user", "DISMISS", "Handled in Foodspace", "DISMISSED"))
                )
            )
        )

        composeRule.onNodeWithTag(FailedSyncDetailTags.HISTORY).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("admin_test_user", substring = true).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Handled in Foodspace", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun aRecordThatCouldNotBeLoaded_explainsWhy_andShowsNoButtons() {
        showDetail(FailedSyncDetailUiState(loading = false, detail = null, notice = DetailNotice.NOT_FOUND))

        composeRule.onNodeWithTag(FailedSyncDetailTags.NOTICE).assertTextContains(text(R.string.admin_failed_notice_not_found))
        composeRule.onNodeWithTag(FailedSyncDetailTags.RETRY).assertDoesNotExist()
    }
}
