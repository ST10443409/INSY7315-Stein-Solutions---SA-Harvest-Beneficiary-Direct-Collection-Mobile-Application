package com.example.client.sync

import com.example.client.data.local.dao.VettingDecisionDao
import com.example.client.data.local.entity.VettingDecision
import com.example.client.network.SyncApiService
import com.example.client.network.VettingSyncRequest
import com.example.client.network.toSyncDto
import javax.inject.Inject

/**
 * Sends vetting decisions (Form 2) to the backend. The same job as [CboSyncProcessor] for a different kind of record:
 * only the database operations and the request differ; how a run decides each decision's fate is the shared
 * [BatchSyncRunner], so decisions get exactly the same retry, rejection and error-code handling as collections.
 */
class VettingSyncProcessor @Inject constructor(
    private val dao: VettingDecisionDao,
    private val api: SyncApiService
) {
    private val runner = BatchSyncRunner(
        store = DecisionStore(),
        idOf = { it.id },
        retryCountOf = { it.retryCount },
        send = { batch -> api.syncVettingDecisions(VettingSyncRequest(batch.map { it.toSyncDto() })) }
    )

    suspend fun syncPending(now: () -> Long = System::currentTimeMillis): SyncRunResult = runner.run(now)

    private inner class DecisionStore : SyncStore<VettingDecision> {
        override suspend fun getSyncable(maxRetries: Int) = dao.getSyncable(maxRetries)
        override suspend fun markSynced(ids: List<String>, now: Long) = dao.markSynced(ids, now)
        override suspend fun markFailed(ids: List<String>, errorCode: String?, now: Long) = dao.markFailed(ids, errorCode, now)
        override suspend fun markRejected(ids: List<String>, errorCode: String?, maxRetries: Int, now: Long) =
            dao.markRejected(ids, errorCode, maxRetries, now)
    }
}
