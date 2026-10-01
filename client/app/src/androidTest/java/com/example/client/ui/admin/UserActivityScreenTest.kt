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
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.client.R
import com.example.client.auth.UserRole
import com.example.client.data.repository.SyncForm
import com.example.client.testing.sampleActivity
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The user activity view (#51), on a device. */
@RunWith(AndroidJUnit4::class)
class UserActivityScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun text(id: Int, vararg args: Any) = composeRule.activity.getString(id, *args)

    private class Taps {
        var refresh = 0
        var apply = 0
        var clear = 0
        var more = 0
        var roles = mutableListOf<UserRole?>()
    }

    private fun show(state: UserActivityUiState, taps: Taps = Taps()) = composeRule.setContent {
        UserActivityScreen(
            state, onBack = {}, onRefresh = { taps.refresh++ }, onUserChange = {}, onRoleChange = { taps.roles += it },
            onFromChange = {}, onToChange = {}, onApply = { taps.apply++ }, onClear = { taps.clear++ }, onLoadMore = { taps.more++ }
        )
    }

    private fun loaded(vararg items: com.example.client.data.repository.ActivityItem, hasMore: Boolean = false, total: Int = items.size) =
        UserActivityUiState(
            loading = false, loaded = true, items = items.toList(), totalCount = total, hasMore = hasMore,
            windowFrom = "2026-09-26", windowTo = "2026-10-02"
        )

    // ── the list ───────────────────────────────────────────────────────────────────

    @Test
    fun eachRow_saysWhoDidWhat_theRecord_theTimeAndTheRole() {
        show(
            loaded(
                sampleActivity("c1", user = "cbo_test_user", role = UserRole.CBO_COLLECTION, label = "Donor c1 · delivery note DN-1"),
                sampleActivity("d1", SyncForm.VETTING_DECISION, user = "vetting_test_user", role = UserRole.VETTING, label = "Approve · beneficiary fs-1")
            )
        )

        composeRule.onNodeWithTag(UserActivityTags.item(SyncForm.CBO_COLLECTION, "c1")).assertIsDisplayed()
            .assertTextContains(text(R.string.admin_activity_did_collection, "cbo_test_user"), substring = true)
            .assertTextContains("Donor c1", substring = true)
            .assertTextContains(text(R.string.admin_activity_role_collector), substring = true)
        composeRule.onNodeWithTag(UserActivityTags.item(SyncForm.VETTING_DECISION, "d1")).assertIsDisplayed()
            .assertTextContains(text(R.string.admin_activity_did_decision, "vetting_test_user"), substring = true)
            .assertTextContains("Approve · beneficiary fs-1", substring = true)
            .assertTextContains(text(R.string.admin_activity_role_vetting), substring = true)
    }

    @Test
    fun aRecordWithNoUser_saysSo_ratherThanShowingBlank() {
        show(loaded(sampleActivity("old", user = null, role = null)))

        composeRule.onNodeWithTag(UserActivityTags.item(SyncForm.CBO_COLLECTION, "old")).assertIsDisplayed()
            .assertTextContains(text(R.string.admin_activity_unknown_user), substring = true)
    }

    @Test
    fun theWindowAndTheCount_areShown_soTheDefaultWindowIsNeverASurprise() {
        show(loaded(sampleActivity("c1"), total = 12))

        composeRule.onNodeWithTag(UserActivityTags.WINDOW).assertIsDisplayed()
            .assertTextContains(text(R.string.admin_activity_window_between, "2026-09-26", "2026-10-02"), substring = true)
            .assertTextContains(text(R.string.admin_activity_count, 12), substring = true)
    }

    @Test
    fun noActivity_isSaidPlainly_notShownAsAnError() {
        show(loaded())

        composeRule.onNodeWithTag(UserActivityTags.EMPTY).performScrollTo().assertIsDisplayed()
            .assertTextContains(text(R.string.admin_activity_empty_title), substring = true)
        composeRule.onNodeWithTag(UserActivityTags.NOTICE).assertDoesNotExist()
    }

    @Test
    fun beforeTheFirstAnswer_itSaysItIsLoading_notThatThereIsNoActivity() {
        show(UserActivityUiState(loading = true))

        composeRule.onNodeWithTag(UserActivityTags.LOADING).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(UserActivityTags.EMPTY).assertDoesNotExist()
        composeRule.onNodeWithTag(UserActivityTags.REFRESH).assertIsNotEnabled()
    }

    @Test
    fun offlineBeforeAnyAnswer_explainsWhy_andNeverClaimsThereIsNoActivity() {
        show(UserActivityUiState(loading = false, loaded = false, notice = AdminNotice.OFFLINE))

        composeRule.onNodeWithTag(UserActivityTags.NOTICE).performScrollTo().assertTextContains(text(R.string.admin_failed_notice_offline))
        composeRule.onNodeWithTag(UserActivityTags.EMPTY).assertDoesNotExist()
    }

    @Test
    fun aFailedLoad_keepsTheEarlierRowsOnScreen() {
        show(loaded(sampleActivity("c1")).copy(notice = AdminNotice.FAILED))

        composeRule.onNodeWithTag(UserActivityTags.NOTICE).performScrollTo().assertTextContains(text(R.string.admin_failed_notice_failed_kept))
        composeRule.onNodeWithTag(UserActivityTags.item(SyncForm.CBO_COLLECTION, "c1")).performScrollTo().assertIsDisplayed()
    }

    // ── paging ─────────────────────────────────────────────────────────────────────

    @Test
    fun showMore_isOffered_onlyWhenThereIsMore() {
        show(loaded(sampleActivity("c1"), hasMore = false))
        composeRule.onNodeWithTag(UserActivityTags.MORE).assertDoesNotExist()
    }

    @Test
    fun showMore_callsItsHandler() {
        val taps = Taps()
        show(loaded(sampleActivity("c1"), hasMore = true, total = 80), taps)

        composeRule.onNodeWithTag(UserActivityTags.MORE).performScrollTo().assertTextContains(text(R.string.admin_activity_more)).performClick()

        assertEquals(1, taps.more)
    }

    // ── the filters ────────────────────────────────────────────────────────────────

    @Test
    fun everyFilterIsOffered_theUser_theRoles_andTheDateRange() {
        show(loaded(sampleActivity("c1")))

        composeRule.onNodeWithTag(UserActivityTags.USER).assertIsDisplayed()
        composeRule.onNodeWithTag(UserActivityTags.role(null)).assertIsDisplayed()
        UserRole.values().forEach { composeRule.onNodeWithTag(UserActivityTags.role(it)).assertIsDisplayed() }
        composeRule.onNodeWithTag(UserActivityTags.FROM).assertIsDisplayed()
        composeRule.onNodeWithTag(UserActivityTags.TO).assertIsDisplayed()
    }

    @Test
    fun tappingARole_asksForThatRole_andAllAsksForNoRole() {
        val taps = Taps()
        show(loaded(sampleActivity("c1")).copy(role = UserRole.ADMIN), taps)

        composeRule.onNodeWithTag(UserActivityTags.role(UserRole.VETTING)).performClick()
        composeRule.onNodeWithTag(UserActivityTags.role(null)).performClick()

        assertEquals(listOf<UserRole?>(UserRole.VETTING, null), taps.roles)
    }

    @Test
    fun applyAndClear_callTheirHandlers() {
        val taps = Taps()
        show(loaded(sampleActivity("c1")), taps)

        composeRule.onNodeWithTag(UserActivityTags.APPLY).performScrollTo().performClick()
        composeRule.onNodeWithTag(UserActivityTags.CLEAR).performScrollTo().performClick()

        assertEquals(1, taps.apply)
        assertEquals(1, taps.clear)
    }

    @Test
    fun aBadDate_isExplainedNextToTheFields() {
        show(loaded(sampleActivity("c1")).copy(from = "tomorrow", dateError = true))

        composeRule.onNodeWithTag(UserActivityTags.DATE_ERROR).performScrollTo().assertIsDisplayed()
            .assertTextContains(text(R.string.admin_activity_date_error))
    }

    @Test
    fun theDateBoxes_takeTypedText() {
        composeRule.setContent {
            // Stateful, like the real screen: what is typed comes back in.
            var state by remember { mutableStateOf(loaded(sampleActivity("c1"))) }
            UserActivityScreen(
                state, onBack = {}, onRefresh = {}, onUserChange = { state = state.copy(user = it) }, onRoleChange = {},
                onFromChange = { state = state.copy(from = it) }, onToChange = { state = state.copy(to = it) },
                onApply = {}, onClear = {}, onLoadMore = {}
            )
        }

        composeRule.onNodeWithTag(UserActivityTags.USER).performScrollTo().performTextInput("vetting_test_user")
        composeRule.onNodeWithTag(UserActivityTags.FROM).performScrollTo().performTextInput("2026-09-01")
        composeRule.onNodeWithTag(UserActivityTags.TO).performScrollTo().performTextInput("2026-09-20")

        composeRule.onNodeWithTag(UserActivityTags.USER).assertTextContains("vetting_test_user")
        composeRule.onNodeWithTag(UserActivityTags.FROM).assertTextContains("2026-09-01")
        composeRule.onNodeWithTag(UserActivityTags.TO).assertTextContains("2026-09-20")
        composeRule.onNodeWithTag(UserActivityTags.APPLY).performScrollTo().assertIsEnabled()
    }

    @Test
    fun refresh_callsItsHandler() {
        val taps = Taps()
        show(loaded(sampleActivity("c1")), taps)

        composeRule.onNodeWithTag(UserActivityTags.REFRESH).performClick()

        assertEquals(1, taps.refresh)
    }
}
