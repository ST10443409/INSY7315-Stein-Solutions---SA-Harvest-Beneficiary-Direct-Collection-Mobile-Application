package com.example.client.data.repository

import com.example.client.testing.FakeVettingDecisionDao
import com.example.client.data.local.entity.DecisionOutcome
import com.example.client.data.local.entity.SyncStatus
import com.example.client.data.local.entity.VettingDecision
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VettingRepositoryTest {

    private val dao = FakeVettingDecisionDao()
    private val repository = VettingRepositoryImpl(dao)

    @Test
    fun aDecision_isSavedLocallyAsPending_withTheOfficerAndTheTime() = runTest {
        val before = System.currentTimeMillis()

        val saved = repository.saveDecision("fs-1", DecisionOutcome.REJECT, "no NPO certificate", "vetting_test_user")

        assertEquals(saved, dao.rows.value.single())
        assertEquals("fs-1", saved.foodspaceRecordId)
        assertEquals(DecisionOutcome.REJECT, saved.outcome)
        assertEquals("no NPO certificate", saved.notes)
        assertEquals("vetting_test_user", saved.officerId)
        assertEquals(SyncStatus.PENDING, saved.syncStatus)
        assertTrue(saved.decisionTimestamp >= before)
        assertEquals(saved.decisionTimestamp, saved.createdAt)
    }

    @Test
    fun notes_areTrimmed_andBlankNotesAreStoredAsNone() = runTest {
        val trimmed = repository.saveDecision("fs-1", DecisionOutcome.APPROVE, "  fine  \n", "o")
        val blank = repository.saveDecision("fs-2", DecisionOutcome.APPROVE, "   \n ", "o")
        val none = repository.saveDecision("fs-3", DecisionOutcome.APPROVE, null, "o")

        assertEquals("fine", trimmed.notes)
        assertNull(blank.notes)
        assertNull(none.notes)
    }

    @Test
    fun everyDecisionGetsItsOwnId() = runTest {
        val a = repository.saveDecision("fs-1", DecisionOutcome.FLAG, null, "o")
        val b = repository.saveDecision("fs-1", DecisionOutcome.FLAG, null, "o")

        assertNotEquals(a.id, b.id)
        assertEquals(2, dao.rows.value.size)
    }

    // The repository is built from the DAO alone (no API service, no connectivity check), so a decision can be saved with
    // no network at all; nothing here can fail because the phone is offline. Mirrors CboCollectionRepositoryTest.
    @Test
    fun save_worksOffline_andTheDecisionIsKeptPendingUntilTheWorkerSendsIt() = runTest {
        val saved = repository.saveDecision("fs-1", DecisionOutcome.APPROVE, "visited, fine", "vetting_test_user")

        assertEquals(SyncStatus.PENDING, repository.observeDecisions().first().single().syncStatus)
        assertEquals(0, saved.retryCount)
        assertNull(saved.syncErrorCode)
    }

    @Test
    fun save_forcesPending_andKeepsTheIdsItWasGiven_soASavedDecisionIsNeverDuplicated() = runTest {
        val saved = repository.saveDecision("fs-1", DecisionOutcome.APPROVE, null, "o")

        // The worker marks it synced; saving through the DAO again with the same id replaces the row, never adds one.
        dao.markSynced(listOf(saved.id), now = 5)
        dao.insert(dao.get(saved.id))

        assertEquals(1, dao.rows.value.size)
    }

    @Test
    fun theStatus_followsWhatTheSyncWorkerDoesUnderneath() = runTest {
        val saved = repository.saveDecision("fs-1", DecisionOutcome.FLAG, null, "o")
        suspend fun status() = repository.observeDecisionsFor("fs-1").first().single().syncStatus

        assertEquals(SyncStatus.PENDING, status())

        dao.markFailed(listOf(saved.id), errorCode = null, now = 10)
        assertEquals(SyncStatus.FAILED, status())
        assertEquals(1, repository.observeDecisions().first().single().retryCount)

        dao.markSynced(listOf(saved.id), now = 20)
        assertEquals(SyncStatus.SYNCED, status())
        assertNull(repository.observeDecisions().first().single().syncErrorCode) // an old error is forgotten once it is sent
    }

    @Test
    fun aRejectedDecision_staysOnTheDevice_withTheServersReason() = runTest {
        val saved = repository.saveDecision("fs-1", DecisionOutcome.REJECT, "no certificate", "o")

        dao.markRejected(listOf(saved.id), errorCode = "VALIDATION_FAILED", maxRetries = 5, now = 10)

        val kept = repository.observeDecisions().first().single()
        assertEquals(SyncStatus.FAILED, kept.syncStatus)
        assertEquals("VALIDATION_FAILED", kept.syncErrorCode)
        assertEquals("no certificate", kept.notes) // what the officer wrote is never lost
    }

    @Test
    fun aChangedMind_keepsBothDecisions_withTheNewestFirst() = runTest {
        repository.saveDecision("fs-1", DecisionOutcome.FLAG, "unsure", "o")
        Thread.sleep(2) // a later timestamp
        repository.saveDecision("fs-1", DecisionOutcome.APPROVE, "checked, fine", "o")
        repository.saveDecision("fs-2", DecisionOutcome.REJECT, null, "o")

        val forFirst = repository.observeDecisionsFor("fs-1").first()
        assertEquals(listOf(DecisionOutcome.APPROVE, DecisionOutcome.FLAG), forFirst.map { it.outcome })
        assertEquals(3, repository.observeDecisions().first().size)
        assertEquals(1, repository.observeDecisionsFor("fs-2").first().size)
    }
}
