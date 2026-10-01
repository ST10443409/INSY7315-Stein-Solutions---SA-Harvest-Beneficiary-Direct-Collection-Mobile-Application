package com.example.client.ui.admin

import com.example.client.data.repository.SyncStatusResult
import com.example.client.testing.FakeAdminSyncStatusRepository
import com.example.client.testing.sampleCounts
import com.example.client.testing.sampleSyncSnapshot
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
class SyncMonitorViewModelTest {

    private lateinit var repository: FakeAdminSyncStatusRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        repository = FakeAdminSyncStatusRepository()
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun opening_loadsTheCounts() = runTest {
        val vm = SyncMonitorViewModel(repository)

        val state = vm.uiState.value
        assertFalse(state.loading)
        assertEquals(sampleSyncSnapshot(), state.snapshot)
        assertNull(state.notice)
        assertEquals(1, repository.loadCalls)
    }

    @Test
    fun whileTheFirstLoadRuns_thereAreNoCountsYet_andNoNotice() = runTest {
        repository.gate = CompletableDeferred()
        val vm = SyncMonitorViewModel(repository)

        assertTrue(vm.uiState.value.loading)
        assertNull(vm.uiState.value.snapshot)
        assertNull(vm.uiState.value.notice)

        repository.gate!!.complete(Unit)
        assertFalse(vm.uiState.value.loading)
        assertEquals(sampleSyncSnapshot(), vm.uiState.value.snapshot)
    }

    @Test
    fun refresh_replacesTheCounts() = runTest {
        val vm = SyncMonitorViewModel(repository)
        val newer = sampleSyncSnapshot(form1 = sampleCounts(forwarded = 99), takenAtMillis = 1_800_000_000_000L)
        repository.result = SyncStatusResult.Loaded(newer)

        vm.refresh()

        assertEquals(newer, vm.uiState.value.snapshot)
        assertEquals(2, repository.loadCalls)
    }

    @Test
    fun aFailedRefresh_keepsTheLastCounts_andSaysWhy() = runTest {
        val vm = SyncMonitorViewModel(repository)

        repository.result = SyncStatusResult.Offline
        vm.refresh()
        assertEquals(MonitorNotice.OFFLINE, vm.uiState.value.notice)
        assertEquals(sampleSyncSnapshot(), vm.uiState.value.snapshot)

        repository.result = SyncStatusResult.Failed
        vm.refresh()
        assertEquals(MonitorNotice.FAILED, vm.uiState.value.notice)
        assertEquals(sampleSyncSnapshot(), vm.uiState.value.snapshot)

        repository.result = SyncStatusResult.Denied
        vm.refresh()
        assertEquals(MonitorNotice.DENIED, vm.uiState.value.notice)
        assertEquals(sampleSyncSnapshot(), vm.uiState.value.snapshot)
    }

    @Test
    fun aSuccessfulRefresh_clearsTheNotice() = runTest {
        repository.result = SyncStatusResult.Offline
        val vm = SyncMonitorViewModel(repository)
        assertEquals(MonitorNotice.OFFLINE, vm.uiState.value.notice)
        assertNull(vm.uiState.value.snapshot)

        repository.result = SyncStatusResult.Loaded(sampleSyncSnapshot())
        vm.refresh()

        assertNull(vm.uiState.value.notice)
        assertEquals(sampleSyncSnapshot(), vm.uiState.value.snapshot)
    }

    @Test
    fun tappingRefreshWhileOneIsRunning_doesNotStartAnother() = runTest {
        repository.gate = CompletableDeferred()
        val vm = SyncMonitorViewModel(repository)

        vm.refresh()
        vm.refresh()

        assertEquals(1, repository.loadCalls)
        repository.gate!!.complete(Unit)
    }
}
