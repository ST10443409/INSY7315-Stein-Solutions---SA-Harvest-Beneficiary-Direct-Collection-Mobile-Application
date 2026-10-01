package com.example.client.ui.admin

import androidx.lifecycle.SavedStateHandle
import com.example.client.data.repository.AdminResult
import com.example.client.data.repository.DuplicateOf
import com.example.client.data.repository.SyncForm
import com.example.client.data.repository.SyncState
import com.example.client.testing.FakeAdminSyncResolutionRepository
import com.example.client.testing.sampleAttention
import com.example.client.testing.sampleDetail
import com.example.client.testing.sampleResolution
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
class FailedSyncViewModelsTest {

    private lateinit var repository: FakeAdminSyncResolutionRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        repository = FakeAdminSyncResolutionRepository()
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    // ── list ───────────────────────────────────────────────────────────────────────

    private fun listVm() = FailedSyncListViewModel(repository)

    @Test
    fun theList_isEmptyAndLoading_untilTheFirstAnswer() = runTest {
        val state = listVm().uiState.value

        assertTrue(state.loading)
        assertFalse(state.loaded)
        assertTrue(state.items.isEmpty())
        assertEquals(0, repository.attentionCalls) // the screen asks when it opens, not the ViewModel
    }

    @Test
    fun refresh_showsTheRecordsThatNeedAttention_inTheOrderGiven() = runTest {
        repository.attentionResult = AdminResult.Success(listOf(sampleAttention("c1"), sampleAttention("d1", SyncForm.VETTING_DECISION)))
        val vm = listVm()

        vm.refresh()

        val state = vm.uiState.value
        assertFalse(state.loading)
        assertTrue(state.loaded)
        assertEquals(listOf("c1", "d1"), state.items.map { it.id })
        assertNull(state.notice)
    }

    @Test
    fun nothingNeedingAttention_isALoadedEmptyList_notAnError() = runTest {
        val vm = listVm()

        vm.refresh()

        assertTrue(vm.uiState.value.loaded)
        assertTrue(vm.uiState.value.items.isEmpty())
        assertNull(vm.uiState.value.notice)
    }

    @Test
    fun aFailedRefresh_keepsWhatWasLoaded_andSaysWhy() = runTest {
        repository.attentionResult = AdminResult.Success(listOf(sampleAttention("c1")))
        val vm = listVm()
        vm.refresh()

        repository.attentionResult = AdminResult.Offline
        vm.refresh()
        assertEquals(AdminNotice.OFFLINE, vm.uiState.value.notice)
        assertEquals(listOf("c1"), vm.uiState.value.items.map { it.id })

        repository.attentionResult = AdminResult.Denied
        vm.refresh()
        assertEquals(AdminNotice.DENIED, vm.uiState.value.notice)

        repository.attentionResult = AdminResult.Failed
        vm.refresh()
        assertEquals(AdminNotice.FAILED, vm.uiState.value.notice)
        assertEquals(listOf("c1"), vm.uiState.value.items.map { it.id })
    }

    @Test
    fun aFirstLoadThatFails_isNotMistakenForAnEmptyList() = runTest {
        repository.attentionResult = AdminResult.Offline
        val vm = listVm()

        vm.refresh()

        assertFalse(vm.uiState.value.loaded)
        assertEquals(AdminNotice.OFFLINE, vm.uiState.value.notice)
    }

    @Test
    fun aSuccessfulRefresh_clearsTheNotice_andReplacesTheList() = runTest {
        repository.attentionResult = AdminResult.Offline
        val vm = listVm()
        vm.refresh()

        repository.attentionResult = AdminResult.Success(listOf(sampleAttention("c2")))
        vm.refresh()

        assertNull(vm.uiState.value.notice)
        assertEquals(listOf("c2"), vm.uiState.value.items.map { it.id })
    }

    // ── detail ─────────────────────────────────────────────────────────────────────

    private fun detailVm(id: String = "c1", form: SyncForm = SyncForm.CBO_COLLECTION) =
        FailedSyncDetailViewModel(SavedStateHandle(mapOf(FAILED_SYNC_ID_ARG to id, FAILED_SYNC_FORM_ARG to form.name)), repository)

    @Test
    fun opening_loadsTheRecord_forTheIdAndKindInTheRoute() = runTest {
        val vm = detailVm("d1", SyncForm.VETTING_DECISION)

        assertEquals(listOf("d1" to SyncForm.VETTING_DECISION), repository.recordCalls)
        assertEquals(sampleDetail(), vm.uiState.value.detail)
        assertFalse(vm.uiState.value.loading)
    }

    @Test
    fun aRecordThatCannotBeLoaded_saysWhy() = runTest {
        repository.recordResult = AdminResult.NotFound
        assertEquals(DetailNotice.NOT_FOUND, detailVm().uiState.value.notice)

        repository.recordResult = AdminResult.Offline
        assertEquals(DetailNotice.OFFLINE, detailVm().uiState.value.notice)

        repository.recordResult = AdminResult.Denied
        assertEquals(DetailNotice.DENIED, detailVm().uiState.value.notice)

        repository.recordResult = AdminResult.Failed
        val vm = detailVm()
        assertEquals(DetailNotice.FAILED, vm.uiState.value.notice)
        assertNull(vm.uiState.value.detail)
    }

    @Test
    fun aRetryThatWorks_showsTheRecordAsTheServerNowHasIt_andSaysItWasSent() = runTest {
        val vm = detailVm()

        vm.retry()

        assertEquals(listOf("c1" to SyncForm.CBO_COLLECTION), repository.retryCalls)
        assertEquals(SyncState.FORWARDED, vm.uiState.value.detail?.state)
        assertEquals(ActionOutcome.SENT, vm.uiState.value.outcome)
        assertFalse(vm.uiState.value.busy)
        assertNull(vm.uiState.value.notice)
    }

    @Test
    fun aRetryThatFoodspaceStillFails_isReportedAsSuch() = runTest {
        val vm = detailVm()
        repository.retryResult = AdminResult.Success(sampleResolution(record = sampleDetail(state = SyncState.RETRYING, nextAttemptMillis = 5L)))
        vm.retry()
        assertEquals(ActionOutcome.STILL_RETRYING, vm.uiState.value.outcome)

        repository.retryResult = AdminResult.Success(sampleResolution(record = sampleDetail(state = SyncState.NEEDS_ATTENTION, attempts = 1)))
        vm.retry()
        assertEquals(ActionOutcome.REJECTED_AGAIN, vm.uiState.value.outcome)
        assertEquals(1, vm.uiState.value.detail?.attempts)

        repository.retryResult = AdminResult.Success(sampleResolution(record = sampleDetail(state = SyncState.SUPERSEDED)))
        vm.retry()
        assertEquals(ActionOutcome.SUPERSEDED, vm.uiState.value.outcome)
    }

    @Test
    fun whileARetryIsRunning_itIsBusy_andASecondTapDoesNothing() = runTest {
        repository.gate = CompletableDeferred()
        val vm = detailVm()

        vm.retry()
        assertTrue(vm.uiState.value.busy)
        vm.retry()

        assertEquals(1, repository.retryCalls.size)
        repository.gate!!.complete(Unit)
        assertFalse(vm.uiState.value.busy)
    }

    @Test
    fun aRecordThatCannotBeRetried_isNotSentAnywhere() = runTest {
        repository.recordResult = AdminResult.Success(sampleDetail(state = SyncState.FORWARDED, error = null))
        val vm = detailVm()

        vm.retry()

        assertTrue(repository.retryCalls.isEmpty())
    }

    @Test
    fun aRetryThatCannotReachTheServer_changesNothing_andSaysSo() = runTest {
        val vm = detailVm()
        repository.retryResult = AdminResult.Offline

        vm.retry()

        assertEquals(DetailNotice.ACTION_OFFLINE, vm.uiState.value.notice)
        assertEquals(sampleDetail(), vm.uiState.value.detail) // still the record as it was
        assertNull(vm.uiState.value.outcome)
        assertFalse(vm.uiState.value.busy)
    }

    @Test
    fun aFailedRetry_isNotMistakenForOneThatWorked() = runTest {
        val vm = detailVm()
        repository.retryResult = AdminResult.Failed

        vm.retry()

        assertEquals(DetailNotice.ACTION_FAILED, vm.uiState.value.notice)
        assertNull(vm.uiState.value.outcome)
    }

    @Test
    fun aConflict_showsTheServersWords_andReloadsTheRecord() = runTest {
        val vm = detailVm()
        repository.retryResult = AdminResult.Conflict("Foodspace already has this record.")
        repository.recordResult = AdminResult.Success(sampleDetail(state = SyncState.FORWARDED, error = null))

        vm.retry()

        assertEquals(DetailNotice.CONFLICT, vm.uiState.value.notice)
        assertEquals("Foodspace already has this record.", vm.uiState.value.serverMessage)
        assertEquals(SyncState.FORWARDED, vm.uiState.value.detail?.state) // now shows what is true, so the buttons go away
        assertEquals(2, repository.recordCalls.size)
    }

    @Test
    fun aRecordThatVanished_isReported() = runTest {
        val vm = detailVm()
        repository.retryResult = AdminResult.NotFound

        vm.retry()

        assertEquals(DetailNotice.NOT_FOUND, vm.uiState.value.notice)
    }

    // ── dismissing ─────────────────────────────────────────────────────────────────

    @Test
    fun dismissing_opensTheReasonBox_andCancelClosesItAndForgetsTheReason() = runTest {
        val vm = detailVm()

        vm.startDismiss()
        vm.onReasonChange("typing")
        assertTrue(vm.uiState.value.dismissing)
        vm.cancelDismiss()

        assertFalse(vm.uiState.value.dismissing)
        assertEquals("", vm.uiState.value.reason)
        assertTrue(repository.dismissCalls.isEmpty())
    }

    @Test
    fun aDismissal_cannotBeConfirmedWithoutAReason() = runTest {
        val vm = detailVm()
        vm.startDismiss()

        assertFalse(vm.uiState.value.canConfirmDismiss)
        vm.confirmDismiss()
        vm.onReasonChange("   ")
        assertFalse(vm.uiState.value.canConfirmDismiss)
        vm.confirmDismiss()

        assertTrue(repository.dismissCalls.isEmpty())
    }

    @Test
    fun aDismissal_sendsTheTrimmedReason_andShowsTheRecordDismissed() = runTest {
        val vm = detailVm("d1", SyncForm.VETTING_DECISION)
        vm.startDismiss()
        vm.onReasonChange("  Beneficiary withdrawn  ")

        vm.confirmDismiss()

        assertEquals(listOf(Triple("d1", SyncForm.VETTING_DECISION, "Beneficiary withdrawn")), repository.dismissCalls)
        assertEquals(SyncState.DISMISSED, vm.uiState.value.detail?.state)
        assertEquals(ActionOutcome.DISMISSED, vm.uiState.value.outcome)
        assertFalse(vm.uiState.value.dismissing)
        assertEquals("", vm.uiState.value.reason)
    }

    @Test
    fun aDismissalThatFails_keepsTheReasonBoxAndWhatWasTyped() = runTest {
        val vm = detailVm()
        vm.startDismiss()
        vm.onReasonChange("Handled in Foodspace")
        repository.dismissResult = AdminResult.Offline

        vm.confirmDismiss()

        assertEquals(DetailNotice.ACTION_OFFLINE, vm.uiState.value.notice)
        assertTrue(vm.uiState.value.dismissing)
        assertEquals("Handled in Foodspace", vm.uiState.value.reason)
        assertEquals(sampleDetail(), vm.uiState.value.detail)
    }

    @Test
    fun theReason_isLimitedToWhatTheServerAccepts() = runTest {
        val vm = detailVm()
        vm.startDismiss()

        vm.onReasonChange("x".repeat(FailedSyncDetailViewModel.MAX_REASON_LENGTH + 50))

        assertEquals(FailedSyncDetailViewModel.MAX_REASON_LENGTH, vm.uiState.value.reason.length)
    }

    @Test
    fun aRecordThatCannotBeDismissed_cannotOpenTheReasonBox() = runTest {
        repository.recordResult = AdminResult.Success(sampleDetail(state = SyncState.RETRYING, nextAttemptMillis = 5L))
        val vm = detailVm()

        vm.startDismiss()

        assertFalse(vm.uiState.value.dismissing)
    }

    @Test
    fun aHeldDuplicate_showsWhatItLooksLike() = runTest {
        val original = DuplicateOf("orig", "Donor orig · delivery note DN-1", SyncState.FORWARDED, 1L)
        repository.recordResult = AdminResult.Success(sampleDetail("dup", state = SyncState.DUPLICATE_HELD, error = null, attempts = 0, duplicateOf = original))

        val detail = detailVm("dup").uiState.value.detail

        assertEquals(original, detail?.duplicateOf)
        assertTrue(detail!!.canRetry && detail.canDismiss)
    }
}
