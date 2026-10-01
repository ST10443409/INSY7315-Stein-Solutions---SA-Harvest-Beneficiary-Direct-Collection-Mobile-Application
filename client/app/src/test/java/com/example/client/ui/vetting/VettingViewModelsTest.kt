package com.example.client.ui.vetting

import androidx.lifecycle.SavedStateHandle
import com.example.client.auth.SessionManager
import com.example.client.auth.UserRole
import com.example.client.data.local.entity.DecisionOutcome
import com.example.client.data.repository.RecordsMeta
import com.example.client.data.repository.RefreshOutcome
import com.example.client.testing.FakeVettingRecordsRepository
import com.example.client.testing.FakeVettingRepository
import com.example.client.testing.InMemoryTokenStorage
import com.example.client.testing.sampleDecision
import com.example.client.testing.sampleRecord
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VettingViewModelsTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var records: FakeVettingRecordsRepository
    private lateinit var vetting: FakeVettingRepository
    private lateinit var session: SessionManager

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        records = FakeVettingRecordsRepository()
        vetting = FakeVettingRepository()
        session = SessionManager(InMemoryTokenStorage()).apply { startSession("jwt", UserRole.VETTING, null, "vetting_test_user") }
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun <T> kotlinx.coroutines.test.TestScope.keepActive(flow: StateFlow<T>) {
        backgroundScope.launch(dispatcher) { flow.collect { } }
    }

    // ── list ───────────────────────────────────────────────────────────────────────

    private fun listVm() = VettingListViewModel(records, vetting)

    @Test
    fun theListShowsTheCachedRecords_withTheCurrentDecisionOnEach() = runTest {
        records.records.value = listOf(sampleRecord("a", legalName = "Alpha"), sampleRecord("b", legalName = "Beta"), sampleRecord("c", legalName = "Gamma"))
        vetting.saveDecision("a", DecisionOutcome.FLAG, null, "o")
        vetting.saveDecision("a", DecisionOutcome.APPROVE, null, "o") // changed their mind: the newest counts
        vetting.saveDecision("b", DecisionOutcome.REJECT, null, "o")
        val vm = listVm()
        keepActive(vm.uiState)

        val state = vm.uiState.value

        assertTrue(state.loaded)
        assertEquals(listOf("a", "b", "c"), state.items.map { it.id })
        assertEquals(listOf(DecisionOutcome.APPROVE, DecisionOutcome.REJECT, null), state.items.map { it.decision })
        assertEquals(2, state.decided)
    }

    @Test
    fun anEmptyCache_isALoadedEmptyList_notAnError() = runTest {
        val vm = listVm()
        keepActive(vm.uiState)

        assertTrue(vm.uiState.value.loaded)
        assertTrue(vm.uiState.value.items.isEmpty())
        assertNull(vm.uiState.value.notice)
    }

    @Test
    fun openingTheList_asksForFreshRecords_andShowsThemWhenTheyArrive() = runTest {
        records.onRefresh = listOf(sampleRecord("new"))
        val vm = listVm()
        keepActive(vm.uiState)

        assertEquals(1, records.refreshCalls)
        assertEquals(listOf("new"), vm.uiState.value.items.map { it.id })
        assertFalse(vm.uiState.value.refreshing)
        assertNull(vm.uiState.value.notice)
    }

    @Test
    fun whileSyncing_theListSaysSo_andTheCachedRecordsAreStillThere() = runTest {
        records.records.value = listOf(sampleRecord("a"))
        records.gate = CompletableDeferred()
        val vm = listVm()
        keepActive(vm.uiState)

        assertTrue(vm.uiState.value.refreshing)
        assertEquals(listOf("a"), vm.uiState.value.items.map { it.id })

        records.gate!!.complete(Unit)
        assertFalse(vm.uiState.value.refreshing)
    }

    @Test
    fun offline_theRecordsStayUsable_withABannerAndNoError() = runTest {
        records.records.value = listOf(sampleRecord("a"), sampleRecord("b"))
        records.outcome = RefreshOutcome.OFFLINE
        val vm = listVm()
        keepActive(vm.uiState)

        val state = vm.uiState.value
        assertEquals(ListNotice.OFFLINE, state.notice)
        assertEquals(2, state.items.size)
        assertFalse(state.refreshing)
    }

    @Test
    fun theOtherFailures_eachGetTheirOwnBanner_andNeverHideTheRecords() = runTest {
        records.records.value = listOf(sampleRecord("a"))
        val expected = mapOf(
            RefreshOutcome.SERVER_UNAVAILABLE to ListNotice.UNAVAILABLE,
            RefreshOutcome.FAILED to ListNotice.FAILED
        )
        expected.forEach { (outcome, notice) ->
            records.outcome = outcome
            val vm = listVm()
            keepActive(vm.uiState)
            assertEquals(notice, vm.uiState.value.notice)
            assertEquals(1, vm.uiState.value.items.size)
        }
    }

    @Test
    fun aStaleCopy_isFlagged_evenWhenTheSyncSucceeded() = runTest {
        records.setMeta(RecordsMeta(fetchedAtMillis = 5L, stale = true))
        val vm = listVm()
        keepActive(vm.uiState)

        assertEquals(ListNotice.STALE, vm.uiState.value.notice)
        assertEquals(5L, vm.uiState.value.meta?.fetchedAtMillis)
    }

    @Test
    fun tappingSync_refreshesAgain_andTheBannerClearsOnceItWorks() = runTest {
        records.outcome = RefreshOutcome.OFFLINE
        val vm = listVm()
        keepActive(vm.uiState)
        assertEquals(ListNotice.OFFLINE, vm.uiState.value.notice)

        records.outcome = RefreshOutcome.UPDATED
        vm.refresh()

        assertEquals(2, records.refreshCalls)
        assertNull(vm.uiState.value.notice)
    }

    @Test
    fun tappingSyncWhileSyncing_doesNotStartASecondFetch() = runTest {
        records.gate = CompletableDeferred()
        val vm = listVm()
        keepActive(vm.uiState)

        vm.refresh()
        vm.refresh()

        assertEquals(1, records.refreshCalls)
    }

    @Test
    fun theLatestDecisionPerRecord_isTheNewestOne() {
        val decisions = listOf(
            sampleDecision("a", DecisionOutcome.FLAG, at = 1),
            sampleDecision("a", DecisionOutcome.APPROVE, at = 5),
            sampleDecision("a", DecisionOutcome.REJECT, at = 3),
            sampleDecision("b", DecisionOutcome.FLAG, at = 2)
        )

        val latest = latestDecisionByRecord(decisions)

        assertEquals(DecisionOutcome.APPROVE, latest.getValue("a").outcome)
        assertEquals(DecisionOutcome.FLAG, latest.getValue("b").outcome)
        assertEquals(2, latest.size)
    }

    // ── detail ─────────────────────────────────────────────────────────────────────

    private fun detail(id: String) = BeneficiaryDetailViewModel(SavedStateHandle(mapOf(VETTING_RECORD_ARG to id)), records, vetting)

    @Test
    fun theDetail_isReady_withTheRecordAndTheCurrentDecision() = runTest {
        records.records.value = listOf(sampleRecord("a"))
        vetting.saveDecision("a", DecisionOutcome.FLAG, null, "o")
        vetting.saveDecision("a", DecisionOutcome.REJECT, "no certificate", "o")
        val vm = detail("a")
        keepActive(vm.uiState)

        val state = vm.uiState.value as DetailUiState.Ready
        assertEquals("a", state.record.id)
        assertEquals(DecisionOutcome.REJECT, state.currentDecision?.outcome)
    }

    @Test
    fun aRecordWithNoDecision_hasNoCurrentDecision() = runTest {
        records.records.value = listOf(sampleRecord("a"))
        val vm = detail("a")
        keepActive(vm.uiState)

        assertNull((vm.uiState.value as DetailUiState.Ready).currentDecision)
    }

    @Test
    fun aRecordThatIsNotOnTheDevice_isNotFound() = runTest {
        records.records.value = listOf(sampleRecord("a"))
        val vm = detail("gone")
        keepActive(vm.uiState)

        assertEquals(DetailUiState.NotFound, vm.uiState.value)
    }

    @Test
    fun theDetail_followsTheDecisionLive() = runTest {
        records.records.value = listOf(sampleRecord("a"))
        val vm = detail("a")
        keepActive(vm.uiState)

        vetting.saveDecision("a", DecisionOutcome.APPROVE, null, "o")

        assertEquals(DecisionOutcome.APPROVE, (vm.uiState.value as DetailUiState.Ready).currentDecision?.outcome)
    }

    // ── decision ───────────────────────────────────────────────────────────────────

    private fun decision(id: String = "a") = DecisionViewModel(SavedStateHandle(mapOf(VETTING_RECORD_ARG to id)), records, vetting, session)

    @Test
    fun theDecisionScreen_knowsWhichRecordItIsFor_andWhatWasDecidedBefore() = runTest {
        records.records.value = listOf(sampleRecord("a", legalName = "Alpha NPO"))
        vetting.saveDecision("a", DecisionOutcome.FLAG, null, "o")

        val vm = decision()

        assertEquals("Alpha NPO", vm.uiState.value.recordName)
        assertEquals(DecisionOutcome.FLAG, vm.uiState.value.previous)
    }

    @Test
    fun savingWithoutChoosing_isRefused_andNothingIsSaved() = runTest {
        val vm = decision()

        vm.onSave()

        assertTrue(vm.uiState.value.chooseError)
        assertTrue(vetting.decisions.value.isEmpty())
        assertFalse(vm.uiState.value.saved)
    }

    @Test
    fun choosing_clearsTheError() = runTest {
        val vm = decision()
        vm.onSave()

        vm.onOutcomeChange(DecisionOutcome.APPROVE)

        assertFalse(vm.uiState.value.chooseError)
    }

    @Test
    fun savingADecision_recordsItLocally_underTheSignedInOfficer() = runTest {
        val vm = decision("a")
        vm.onOutcomeChange(DecisionOutcome.REJECT)
        vm.onNotesChange("  missing NPO certificate  ")

        vm.onSave()

        val saved = vetting.decisions.value.single()
        assertEquals("a", saved.foodspaceRecordId)
        assertEquals(DecisionOutcome.REJECT, saved.outcome)
        assertEquals("missing NPO certificate", saved.notes)
        assertEquals("vetting_test_user", saved.officerId)
        assertTrue(vm.uiState.value.saved)
        assertFalse(vm.uiState.value.isSaving)
    }

    @Test
    fun notesAreOptional() = runTest {
        val vm = decision()
        vm.onOutcomeChange(DecisionOutcome.APPROVE)

        vm.onSave()

        assertNull(vetting.decisions.value.single().notes)
        assertTrue(vm.uiState.value.saved)
    }

    @Test
    fun withoutAUsernameInTheSession_theOfficerIsUnknown_notBlank() = runTest {
        session.startSession("jwt-2", UserRole.VETTING) // a session from before the username was kept
        val vm = decision()
        vm.onOutcomeChange(DecisionOutcome.FLAG)

        vm.onSave()

        assertEquals(DecisionViewModel.UNKNOWN_OFFICER, vetting.decisions.value.single().officerId)
    }

    @Test
    fun notes_areCappedAtTheirMaximumLength() = runTest {
        val vm = decision()

        vm.onNotesChange("x".repeat(DecisionViewModel.MAX_NOTES_LENGTH + 500))

        assertEquals(DecisionViewModel.MAX_NOTES_LENGTH, vm.uiState.value.notes.length)
    }

    @Test
    fun aFailedLocalSave_keepsTheOfficersWork_andCanBeRetried() = runTest {
        val vm = decision()
        vm.onOutcomeChange(DecisionOutcome.APPROVE)
        vm.onNotesChange("all good")
        vetting.failWith = IllegalStateException("disk full")

        vm.onSave()

        assertTrue(vm.uiState.value.saveFailed)
        assertFalse(vm.uiState.value.saved)
        assertEquals("all good", vm.uiState.value.notes)
        assertEquals(DecisionOutcome.APPROVE, vm.uiState.value.outcome)

        vetting.failWith = null
        vm.onSave()

        assertTrue(vm.uiState.value.saved)
        assertFalse(vm.uiState.value.saveFailed)
        assertEquals(1, vetting.decisions.value.size)
    }

    @Test
    fun savingTwice_savesOnce() = runTest {
        val vm = decision()
        vm.onOutcomeChange(DecisionOutcome.APPROVE)

        vm.onSave()
        vm.onSave()

        assertEquals(1, vetting.decisions.value.size)
    }
}
