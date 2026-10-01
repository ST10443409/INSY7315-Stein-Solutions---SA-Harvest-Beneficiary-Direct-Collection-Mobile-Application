package com.example.client.data.repository

import com.example.client.data.local.dao.VettingDecisionDao
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

    private class FakeDao : VettingDecisionDao {
        val rows = MutableStateFlow<List<VettingDecision>>(emptyList())
        override suspend fun insert(decision: VettingDecision) {
            rows.value = rows.value.filterNot { it.id == decision.id } + decision
        }

        override suspend fun update(decision: VettingDecision) = insert(decision)
        override fun getBySyncStatus(status: SyncStatus): Flow<List<VettingDecision>> = rows.map { l -> l.filter { it.syncStatus == status } }
        override fun getAll(): Flow<List<VettingDecision>> = rows
        override fun observeAllNewestFirst(): Flow<List<VettingDecision>> =
            rows.map { l -> l.sortedWith(compareByDescending<VettingDecision> { it.decisionTimestamp }.thenByDescending { it.createdAt }) }

        override fun observeForRecord(recordId: String): Flow<List<VettingDecision>> =
            observeAllNewestFirst().map { l -> l.filter { it.foodspaceRecordId == recordId } }
    }

    private val dao = FakeDao()
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
