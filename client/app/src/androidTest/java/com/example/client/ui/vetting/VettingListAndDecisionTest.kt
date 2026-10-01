package com.example.client.ui.vetting

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.client.R
import com.example.client.auth.SessionManager
import com.example.client.auth.UserRole
import com.example.client.data.local.entity.DecisionOutcome
import com.example.client.data.repository.RecordsMeta
import com.example.client.data.repository.RefreshOutcome
import com.example.client.testing.FakeVettingRecordsRepository
import com.example.client.testing.FakeVettingRepository
import com.example.client.testing.InMemoryTokenStorage
import com.example.client.testing.sampleRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The Vetting list and the decision screen, on a device, with the real ViewModels over in-memory repositories. */
@RunWith(AndroidJUnit4::class)
class VettingListAndDecisionTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun text(id: Int) = composeRule.activity.getString(id)

    private fun item(id: String, name: String, decision: DecisionOutcome? = null) =
        RecordItem(id = id, legalName = name, province = "Gauteng", contactName = "Nomsa", decision = decision)

    private fun showList(state: VettingListUiState, onSync: () -> Unit = {}, onOpen: (String) -> Unit = {}, now: Long = 10 * 60_000L) {
        composeRule.setContent { VettingListScreen(state, onSync, onOpen, now = { now }) }
    }

    // ── list: states ───────────────────────────────────────────────────────────────

    @Test
    fun withNoCachedRecords_theOfficerIsToldToConnectAndSync() {
        showList(VettingListUiState(loaded = true))

        composeRule.onNodeWithTag(ListTags.EMPTY).assertIsDisplayed()
        composeRule.onNodeWithTag(ListTags.EMPTY).assertTextContains(text(R.string.vetting_empty_title))
        composeRule.onNodeWithTag(ListTags.EMPTY).assertTextContains(text(R.string.vetting_empty_body))
        composeRule.onNodeWithTag(ListTags.SYNC).assertIsEnabled()
    }

    @Test
    fun theFirstFetch_showsALoadingState_notTheEmptyMessage() {
        showList(VettingListUiState(loaded = true, refreshing = true))

        composeRule.onNodeWithTag(ListTags.LOADING).assertTextContains(text(R.string.vetting_loading))
        composeRule.onNodeWithTag(ListTags.EMPTY).assertDoesNotExist()
        composeRule.onNodeWithTag(ListTags.SYNC).assertIsNotEnabled()
    }

    @Test
    fun beforeTheCacheHasAnswered_neitherEmptyNorListIsShown() {
        showList(VettingListUiState(loaded = false))

        composeRule.onNodeWithTag(ListTags.EMPTY).assertDoesNotExist()
        composeRule.onNodeWithTag(ListTags.LIST).assertDoesNotExist()
    }

    @Test
    fun theCachedRecords_areListed_withTheirDecisions_andASummary() {
        val state = VettingListUiState(
            loaded = true,
            items = listOf(item("a", "Alpha NPO", DecisionOutcome.APPROVE), item("b", "Beta Kitchen"), item("c", "Gamma Hope", DecisionOutcome.FLAG)),
            meta = RecordsMeta(fetchedAtMillis = 5 * 60_000L, stale = false)
        )
        showList(state)

        listOf("a", "b", "c").forEach { composeRule.onNodeWithTag(ListTags.item(it)).assertIsDisplayed() }
        composeRule.onNodeWithTag(ListTags.item("a")).assertTextContains(text(R.string.vetting_outcome_approve))
        composeRule.onNodeWithTag(ListTags.item("b")).assertTextContains(text(R.string.vetting_decision_none))
        composeRule.onNodeWithTag(ListTags.item("c")).assertTextContains(text(R.string.vetting_outcome_flag))
        composeRule.onNodeWithTag(ListTags.item("a")).assertTextContains("Alpha NPO")
        composeRule.onNodeWithTag(ListTags.item("a")).assertTextContains("Gauteng", substring = true)
        // "3 records · 2 decided · updated 5 min ago"
        composeRule.onNodeWithText("3 records · 2 decided", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("5 min ago", substring = true).assertIsDisplayed()
    }

    @Test
    fun tappingARecord_opensIt() {
        var opened: String? = null
        showList(VettingListUiState(loaded = true, items = listOf(item("a", "Alpha"), item("b", "Beta"))), onOpen = { opened = it })

        composeRule.onNodeWithTag(ListTags.item("b")).performClick()

        assertEquals("b", opened)
    }

    @Test
    fun tappingSync_asksForASync() {
        var syncs = 0
        showList(VettingListUiState(loaded = true), onSync = { syncs++ })

        composeRule.onNodeWithTag(ListTags.SYNC).performClick()

        assertEquals(1, syncs)
    }

    @Test
    fun offline_aBannerExplains_andTheRecordsAreStillThereAndTappable() {
        var opened: String? = null
        showList(
            VettingListUiState(loaded = true, items = listOf(item("a", "Alpha")), notice = ListNotice.OFFLINE),
            onOpen = { opened = it }
        )

        composeRule.onNodeWithTag(ListTags.NOTICE).assertTextContains(text(R.string.vetting_notice_offline))
        composeRule.onNodeWithTag(ListTags.item("a")).assertIsDisplayed().performClick()
        assertEquals("a", opened)
        composeRule.onNodeWithTag(ListTags.SYNC).assertIsEnabled()
    }

    @Test
    fun everyKindOfNotice_hasItsOwnMessage() {
        val expected = mapOf(
            ListNotice.OFFLINE to R.string.vetting_notice_offline,
            ListNotice.UNAVAILABLE to R.string.vetting_notice_unavailable,
            ListNotice.FAILED to R.string.vetting_notice_failed,
            ListNotice.STALE to R.string.vetting_notice_stale
        )
        var current by androidx.compose.runtime.mutableStateOf(ListNotice.OFFLINE)
        composeRule.setContent { VettingListScreen(VettingListUiState(loaded = true, items = listOf(item("a", "A")), notice = current), {}, {}) }

        expected.forEach { (notice, res) ->
            composeRule.runOnUiThread { current = notice }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag(ListTags.NOTICE).assertTextContains(text(res))
        }
    }

    @Test
    fun theRealListViewModel_showsTheCache_whenTheSyncFailsOffline() {
        val records = FakeVettingRecordsRepository(listOf(sampleRecord("a", legalName = "Alpha NPO"), sampleRecord("b", legalName = "Beta")))
        records.outcome = RefreshOutcome.OFFLINE
        val vetting = FakeVettingRepository()
        composeRule.setContent {
            val vm = androidx.compose.runtime.remember { VettingListViewModel(records, vetting) }
            val state by vm.uiState.collectAsState()
            VettingListScreen(state, vm::refresh, {})
        }

        composeRule.waitUntil(5_000) { records.refreshCalls >= 1 }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(ListTags.NOTICE).assertTextContains(text(R.string.vetting_notice_offline))
        composeRule.onNodeWithTag(ListTags.item("a")).assertIsDisplayed()
        composeRule.onNodeWithTag(ListTags.item("b")).assertIsDisplayed()
    }

    // ── decision screen ────────────────────────────────────────────────────────────

    private lateinit var vetting: FakeVettingRepository

    private fun showDecision(previous: DecisionOutcome? = null, onBack: () -> Unit = {}) {
        val records = FakeVettingRecordsRepository(listOf(sampleRecord("a", legalName = "Alpha NPO")))
        vetting = FakeVettingRepository()
        previous?.let { kotlinx.coroutines.runBlocking { vetting.saveDecision("a", it, null, "officer") } }
        val session = SessionManager(InMemoryTokenStorage()).apply { startSession("jwt", UserRole.VETTING, null, "vetting_test_user") }
        composeRule.setContent {
            val vm = androidx.compose.runtime.remember { DecisionViewModel(SavedStateHandle(mapOf(VETTING_RECORD_ARG to "a")), records, vetting, session) }
            val state by vm.uiState.collectAsState()
            DecisionScreen(state, onBack, vm::onOutcomeChange, vm::onNotesChange, vm::onSave)
        }
    }

    @Test
    fun theThreeChoices_areOffered_andChoosingOneDeselectsTheOthers() {
        showDecision()

        DecisionOutcome.values().forEach { composeRule.onNodeWithTag(DecisionTags.option(it)).assertIsDisplayed().assertIsNotSelected() }

        composeRule.onNodeWithTag(DecisionTags.option(DecisionOutcome.REJECT)).performClick()
        composeRule.onNodeWithTag(DecisionTags.option(DecisionOutcome.REJECT)).assertIsSelected()
        composeRule.onNodeWithTag(DecisionTags.option(DecisionOutcome.APPROVE)).assertIsNotSelected()

        composeRule.onNodeWithTag(DecisionTags.option(DecisionOutcome.FLAG)).performClick()
        composeRule.onNodeWithTag(DecisionTags.option(DecisionOutcome.FLAG)).assertIsSelected()
        composeRule.onNodeWithTag(DecisionTags.option(DecisionOutcome.REJECT)).assertIsNotSelected()
    }

    @Test
    fun savingWithoutChoosing_explains_andSavesNothing() {
        showDecision()

        composeRule.onNodeWithTag(DecisionTags.SAVE).performScrollTo().performClick()

        composeRule.onNodeWithTag(DecisionTags.ERROR_CHOOSE).assertTextContains(text(R.string.vetting_err_choose))
        assertTrue(vetting.decisions.value.isEmpty())
    }

    @Test
    fun aChosenDecisionWithNotes_isSavedLocally_andTheOfficerIsTold() {
        showDecision()

        composeRule.onNodeWithTag(DecisionTags.option(DecisionOutcome.REJECT)).performClick()
        composeRule.onNodeWithTag(DecisionTags.NOTES).performScrollTo().performTextInput("No NPO certificate on file")
        composeRule.onNodeWithTag(DecisionTags.SAVE).performScrollTo().performClick()

        composeRule.waitForIdle()
        composeRule.onNodeWithTag(DecisionTags.SAVED).assertTextContains(text(R.string.vetting_saved_body))
        val saved = vetting.decisions.value.single()
        assertEquals(DecisionOutcome.REJECT, saved.outcome)
        assertEquals("No NPO certificate on file", saved.notes)
        assertEquals("vetting_test_user", saved.officerId)
        assertEquals("a", saved.foodspaceRecordId)
    }

    @Test
    fun afterSaving_theOfficerCanGoBackToTheRecord() {
        var backs = 0
        showDecision(onBack = { backs++ })
        composeRule.onNodeWithTag(DecisionTags.option(DecisionOutcome.APPROVE)).performClick()
        composeRule.onNodeWithTag(DecisionTags.SAVE).performScrollTo().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(DecisionTags.DONE).performClick()

        assertEquals(1, backs)
    }

    @Test
    fun anEarlierDecision_isMentioned_soChangingItIsDeliberate() {
        showDecision(previous = DecisionOutcome.FLAG)

        composeRule.waitForIdle()
        composeRule.onNodeWithText("Your last decision on this record: ${text(R.string.vetting_outcome_flag)}", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Alpha NPO").assertIsDisplayed()
    }
}
