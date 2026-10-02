package com.example.client.data.repository

import com.example.client.data.local.dao.VettingDecisionDao
import com.example.client.data.local.entity.DecisionOutcome
import com.example.client.data.local.entity.SyncStatus
import com.example.client.data.local.entity.VettingDecision
import com.example.client.sync.SyncPolicy
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VettingRepositoryImpl @Inject constructor(
    private val dao: VettingDecisionDao
) : VettingRepository {

    override suspend fun saveDecision(
        recordId: String,
        outcome: DecisionOutcome,
        notes: String?,
        officerId: String
    ): VettingDecision {
        val now = System.currentTimeMillis()
        val decision = VettingDecision(
            id = UUID.randomUUID().toString(),
            foodspaceRecordId = recordId,
            outcome = outcome,
            notes = notes?.trim()?.takeIf { it.isNotEmpty() },
            officerId = officerId,
            decisionTimestamp = now,
            syncStatus = SyncStatus.PENDING,
            createdAt = now,
            updatedAt = now
        )
        dao.insert(decision)
        return decision
    }

    override fun observeDecisions(): Flow<List<VettingDecision>> = dao.observeAllNewestFirst()

    override fun observeDecisionsBy(officer: String?): Flow<List<VettingDecision>> = dao.observeByOfficerNewestFirst(officer)

    override fun observeWaitingForOthers(officer: String?): Flow<Int> =
        dao.observeWaitingForOtherOfficers(officer, SyncPolicy.MAX_RETRIES)

    override fun observeDecisionsFor(recordId: String): Flow<List<VettingDecision>> = dao.observeForRecord(recordId)
}
