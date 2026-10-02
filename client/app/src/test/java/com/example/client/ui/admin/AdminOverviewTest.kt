package com.example.client.ui.admin

import com.example.client.auth.FakeTokenStorage
import com.example.client.auth.SessionManager
import com.example.client.auth.UserRole
import com.example.client.data.repository.SyncStatusResult
import com.example.client.testing.FakeAdminSyncStatusRepository
import com.example.client.testing.sampleCounts
import com.example.client.testing.sampleSyncSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** What the Admin Overview flags comes from the server's own counts, and nothing else. */
@OptIn(ExperimentalCoroutinesApi::class)
class AdminOverviewTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var repository: FakeAdminSyncStatusRepository
    private lateinit var viewModel: AdminOverviewViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repository = FakeAdminSyncStatusRepository()
        val session = SessionManager(FakeTokenStorage()).apply { startSession("jwt", UserRole.ADMIN, username = "admin_user") }
        viewModel = AdminOverviewViewModel(repository, session)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun beforeTheServerHasAnswered_nothingIsFlagged() {
        val state = viewModel.uiState.value
        assertTrue(state.loading)
        assertNull(state.snapshot)
        assertEquals(0, state.needsAttention)
        assertTrue(state.alerts.isEmpty())
        assertEquals("admin_user", state.username)
    }

    @Test
    fun theServersCounts_becomeTheAlerts_worstFirst() = runTest(dispatcher) {
        repository.result = SyncStatusResult.Loaded(
            sampleSyncSnapshot(
                form1 = sampleCounts(waiting = 2, retrying = 1, needsAttention = 3, duplicates = 4),
                form2 = sampleCounts(waiting = 1, needsAttention = 5)
            )
        )

        viewModel.refresh()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(8, state.needsAttention)
        assertEquals(
            listOf(
                AdminAlert(AdminAlertKind.COLLECTIONS_NEED_ATTENTION, 3),
                AdminAlert(AdminAlertKind.DECISIONS_NEED_ATTENTION, 5),
                AdminAlert(AdminAlertKind.DUPLICATE_COLLECTIONS, 4),
                AdminAlert(AdminAlertKind.WAITING_TO_SYNC, 4)
            ),
            state.alerts
        )
    }

    @Test
    fun whenEverythingHasReachedTheServer_nothingIsFlagged() = runTest(dispatcher) {
        repository.result = SyncStatusResult.Loaded(sampleSyncSnapshot(form1 = sampleCounts(forwarded = 9), form2 = sampleCounts(forwarded = 4)))

        viewModel.refresh()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.alerts.isEmpty())
        assertEquals(0, viewModel.uiState.value.needsAttention)
    }

    @Test
    fun aFailedRefresh_keepsTheCountsAlreadyShown_andSaysWhy() = runTest(dispatcher) {
        viewModel.refresh()
        advanceUntilIdle()
        val before = viewModel.uiState.value.snapshot
        assertNotNull(before)

        repository.result = SyncStatusResult.Offline
        viewModel.refresh()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(before, state.snapshot)
        assertEquals(AdminNotice.OFFLINE, state.notice)
    }

    @Test
    fun aDeniedAccount_isReported_withNoCounts() = runTest(dispatcher) {
        repository.result = SyncStatusResult.Denied

        viewModel.refresh()
        advanceUntilIdle()

        assertEquals(AdminNotice.DENIED, viewModel.uiState.value.notice)
        assertNull(viewModel.uiState.value.snapshot)
    }
}
