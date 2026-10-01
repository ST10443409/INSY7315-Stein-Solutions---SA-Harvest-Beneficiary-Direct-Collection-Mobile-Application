package com.example.client.ui.admin

import com.example.client.auth.UserRole
import com.example.client.data.repository.ActivityFilter
import com.example.client.data.repository.AdminResult
import com.example.client.testing.FakeAdminUserActivityRepository
import com.example.client.testing.sampleActivity
import com.example.client.testing.sampleActivityPage
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
class UserActivityViewModelTest {

    private lateinit var repository: FakeAdminUserActivityRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        repository = FakeAdminUserActivityRepository()
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun vm() = UserActivityViewModel(repository)

    // ── opening ────────────────────────────────────────────────────────────────────

    @Test
    fun opening_loadsTheFirstPage_withNoFilters_soTheServerAppliesItsRecentWindow() = runTest {
        repository.result = AdminResult.Success(sampleActivityPage(listOf(sampleActivity("c1"), sampleActivity("d1")), totalCount = 2))

        val state = vm().uiState.value

        assertEquals(listOf(ActivityFilter() to 1), repository.calls)
        assertEquals(listOf("c1", "d1"), state.items.map { it.id })
        assertTrue(state.loaded)
        assertFalse(state.loading)
        assertEquals("2026-09-26", state.windowFrom)
        assertEquals("2026-10-02", state.windowTo)
    }

    @Test
    fun beforeTheFirstAnswer_itIsLoading_notEmpty() = runTest {
        repository.gate = CompletableDeferred()

        val state = vm().uiState.value

        assertTrue(state.loading)
        assertFalse(state.loaded)
        repository.gate!!.complete(Unit)
    }

    @Test
    fun noActivity_isALoadedEmptyList_notAnError() = runTest {
        repository.result = AdminResult.Success(sampleActivityPage(emptyList()))

        val state = vm().uiState.value

        assertTrue(state.loaded)
        assertTrue(state.items.isEmpty())
        assertNull(state.notice)
    }

    @Test
    fun aFirstLoadThatFails_isNotMistakenForNoActivity() = runTest {
        repository.result = AdminResult.Offline

        val state = vm().uiState.value

        assertFalse(state.loaded)
        assertEquals(AdminNotice.OFFLINE, state.notice)
    }

    // ── filtering ──────────────────────────────────────────────────────────────────

    @Test
    fun applying_sendsWhatIsTyped_fromTheFirstPage() = runTest {
        val vm = vm()
        vm.onUserChange("  vetting_test_user ")
        vm.onFromChange("2026-09-01")
        vm.onToChange("2026-09-20")

        vm.apply()

        assertEquals(ActivityFilter("vetting_test_user", null, "2026-09-01", "2026-09-20") to 1, repository.calls.last())
        assertEquals(ActivityFilter("vetting_test_user", null, "2026-09-01", "2026-09-20"), vm.uiState.value.applied)
    }

    @Test
    fun typing_doesNotSearchUntilApplied() = runTest {
        val vm = vm()

        vm.onUserChange("someone")
        vm.onFromChange("2026-09-01")

        assertEquals(1, repository.calls.size) // only the opening load
    }

    @Test
    fun aRoleChip_appliesAtOnce_withWhatElseIsTyped() = runTest {
        val vm = vm()
        vm.onUserChange("someone")

        vm.onRoleChange(UserRole.VETTING)

        assertEquals(ActivityFilter("someone", UserRole.VETTING, null, null) to 1, repository.calls.last())
        assertEquals(UserRole.VETTING, vm.uiState.value.role)
    }

    @Test
    fun anyRole_isNull_andApplies() = runTest {
        val vm = vm()
        vm.onRoleChange(UserRole.ADMIN)

        vm.onRoleChange(null)

        assertEquals(ActivityFilter() to 1, repository.calls.last())
        assertNull(vm.uiState.value.role)
    }

    @Test
    fun clear_emptiesEveryFilter_andLoadsTheDefaultWindowAgain() = runTest {
        val vm = vm()
        vm.onUserChange("someone")
        vm.onFromChange("2026-09-01")
        vm.onRoleChange(UserRole.ADMIN)

        vm.clear()

        val state = vm.uiState.value
        assertEquals("", state.user)
        assertEquals("", state.from)
        assertNull(state.role)
        assertEquals(ActivityFilter() to 1, repository.calls.last())
    }

    @Test
    fun aNewFilter_replacesTheList_notAddsToIt() = runTest {
        repository.handler = { filter, _ ->
            AdminResult.Success(sampleActivityPage(listOf(sampleActivity(if (filter.user == null) "all" else "mine")), totalCount = 1))
        }
        val vm = vm()

        vm.onUserChange("x")
        vm.apply()

        assertEquals(listOf("mine"), vm.uiState.value.items.map { it.id })
    }

    // ── dates ──────────────────────────────────────────────────────────────────────

    @Test
    fun aDateTheServerWouldRefuse_isCaughtHere_andNothingIsSent() = runTest {
        val vm = vm()
        val before = repository.calls.size

        for (bad in listOf("yesterday", "2026-13-40", "20-09-2026", "2026-02-30", "1999-12-31", "2101-01-01")) {
            vm.onFromChange(bad)
            vm.apply()
            assertTrue("$bad should be refused", vm.uiState.value.dateError)
        }

        assertEquals(before, repository.calls.size)
    }

    @Test
    fun aFirstDayAfterTheLast_isCaught() = runTest {
        val vm = vm()
        vm.onFromChange("2026-09-20")
        vm.onToChange("2026-09-10")

        vm.apply()

        assertTrue(vm.uiState.value.dateError)
        assertEquals(1, repository.calls.size)
    }

    @Test
    fun correctingADate_clearsTheError() = runTest {
        val vm = vm()
        vm.onFromChange("nope")
        vm.apply()
        assertTrue(vm.uiState.value.dateError)

        vm.onFromChange("2026-09-01")

        assertFalse(vm.uiState.value.dateError)
    }

    @Test
    fun oneDateAlone_isFine_andSameDayIsFine() = runTest {
        val vm = vm()
        vm.onFromChange("2026-09-10")
        vm.apply()
        assertFalse(vm.uiState.value.dateError)

        vm.onToChange("2026-09-10")
        vm.apply()

        assertFalse(vm.uiState.value.dateError)
        assertEquals(ActivityFilter(null, null, "2026-09-10", "2026-09-10") to 1, repository.calls.last())
    }

    // ── paging ─────────────────────────────────────────────────────────────────────

    @Test
    fun showMore_addsTheNextPage_ofTheFilterBeingShown() = runTest {
        repository.handler = { _, page ->
            AdminResult.Success(
                sampleActivityPage(listOf(sampleActivity("p$page-a"), sampleActivity("p$page-b")), page = page, totalCount = 4, hasMore = page < 2)
            )
        }
        val vm = vm()
        vm.onUserChange("typed but not applied")

        vm.loadMore()

        assertEquals(listOf("p1-a", "p1-b", "p2-a", "p2-b"), vm.uiState.value.items.map { it.id })
        assertFalse(vm.uiState.value.hasMore)
        assertEquals(ActivityFilter() to 2, repository.calls.last()) // the applied filter, not what is being typed
    }

    @Test
    fun showMore_doesNothingWhenThereIsNoMore_orOneIsAlreadyRunning() = runTest {
        val vm = vm() // hasMore = false
        vm.loadMore()
        assertEquals(1, repository.calls.size)

        repository.result = AdminResult.Success(sampleActivityPage(hasMore = true))
        val vm2 = vm()
        repository.gate = CompletableDeferred()
        vm2.loadMore()
        vm2.loadMore()

        assertEquals(1 + 1 + 1, repository.calls.size) // the first VM's open, the second's open, and one show-more
        repository.gate!!.complete(Unit)
    }

    @Test
    fun aRecordThatMovedBetweenPages_isShownOnce() = runTest {
        repository.handler = { _, page ->
            AdminResult.Success(sampleActivityPage(listOf(sampleActivity("dup"), sampleActivity("only-$page")), page = page, hasMore = page == 1))
        }
        val vm = vm()

        vm.loadMore()

        assertEquals(listOf("dup", "only-1", "only-2"), vm.uiState.value.items.map { it.id })
    }

    @Test
    fun aFailedShowMore_keepsTheList_andSaysWhy() = runTest {
        repository.handler = { _, page ->
            if (page == 1) AdminResult.Success(sampleActivityPage(listOf(sampleActivity("c1")), hasMore = true)) else AdminResult.Offline
        }
        val vm = vm()

        vm.loadMore()

        assertEquals(listOf("c1"), vm.uiState.value.items.map { it.id })
        assertEquals(AdminNotice.OFFLINE, vm.uiState.value.notice)
        assertTrue(vm.uiState.value.hasMore) // can try again
        assertFalse(vm.uiState.value.loadingMore)
    }

    // ── failures and races ─────────────────────────────────────────────────────────

    @Test
    fun aFailedFilter_keepsWhatWasShown_andSaysWhy() = runTest {
        val vm = vm()
        repository.result = AdminResult.Failed

        vm.onRoleChange(UserRole.VETTING)

        assertEquals(listOf("c1"), vm.uiState.value.items.map { it.id })
        assertEquals(AdminNotice.FAILED, vm.uiState.value.notice)
        assertEquals(ActivityFilter(), vm.uiState.value.applied) // the list still shows the old filter
    }

    @Test
    fun aRefusedAccount_isReported() = runTest {
        repository.result = AdminResult.Denied

        assertEquals(AdminNotice.DENIED, vm().uiState.value.notice)
    }

    @Test
    fun aSlowAnswerForAnOldFilter_isDropped_soItCanNeverAppearUnderANewOne() = runTest {
        val slow = CompletableDeferred<Unit>()
        repository.handler = { filter, _ ->
            if (filter.role == null) slow.await()
            AdminResult.Success(sampleActivityPage(listOf(sampleActivity(if (filter.role == null) "old-filter" else "new-filter"))))
        }
        val vm = vm() // opens with no role and waits for `slow`

        vm.onRoleChange(UserRole.VETTING) // a newer filter answers straight away
        slow.complete(Unit) // now the old one finishes

        assertEquals(listOf("new-filter"), vm.uiState.value.items.map { it.id })
    }

    @Test
    fun aSuccessfulLoad_clearsAnEarlierNotice() = runTest {
        repository.result = AdminResult.Offline
        val vm = vm()
        assertEquals(AdminNotice.OFFLINE, vm.uiState.value.notice)

        repository.result = AdminResult.Success(sampleActivityPage())
        vm.apply()

        assertNull(vm.uiState.value.notice)
    }

    // ── the date check itself ──────────────────────────────────────────────────────

    @Test
    fun theDateCheck_acceptsRealDays_andOnlyThose() {
        for (ok in listOf("2026-09-20", "2000-01-01", "2100-12-31", "2024-02-29")) assertTrue(ok, isValidActivityDate(ok))
        for (bad in listOf("", "2026-9-20", "2026-09-2", "2026/09/20", "2026-02-30", "2025-02-29", "2026-00-10", "1999-12-31", "2101-01-01", "tomorrow", "2026-09-20 ")) {
            assertFalse(bad, isValidActivityDate(bad))
        }
    }
}
