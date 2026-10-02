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
    /**
     * Sends [officer]'s waiting decisions, and only theirs (#70): the request carries the signed-in user's token and the
     * server records every decision in it as made by that user, so another officer's decisions stay as they are until
     * they sign in again.
     */
    suspend fun syncPending(officer: String, now: () -> Long = System::currentTimeMillis): SyncRunResult =
        BatchSyncRunner(
            store = DecisionStore(officer),
            idOf = { it.id },
            retryCountOf = { it.retryCount },
            send = { batch -> api.syncVettingDecisions(VettingSyncRequest(batch.map { it.toSyncDto() })) }
        ).run(now)

    private inner class DecisionStore(private val officer: String) : SyncStore<VettingDecision> {
        override suspend fun getSyncable(maxRetries: Int) = dao.getSyncable(maxRetries, officer)
        override suspend fun markSynced(ids: List<String>, now: Long) = dao.markSynced(ids, now)
        override suspend fun markFailed(ids: List<String>, errorCode: String?, now: Long) = dao.markFailed(ids, errorCode, now)
        override suspend fun markRejected(ids: List<String>, errorCode: String?, maxRetries: Int, now: Long) =
            dao.markRejected(ids, errorCode, maxRetries, now)
    }
}
