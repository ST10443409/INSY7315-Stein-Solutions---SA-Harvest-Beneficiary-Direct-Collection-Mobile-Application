package com.example.client.sync

import com.example.client.data.local.dao.CboCollectionDao
import com.example.client.data.local.dao.ProductLineDao
import com.example.client.data.local.entity.CboCollectionEntity
import com.example.client.data.local.entity.SyncStatus
import com.example.client.network.CboSyncRequest
import com.example.client.network.SyncApiService
import com.example.client.network.toSyncDto
import javax.inject.Inject

/**
 * Sends CBO collections (Form 1) to the backend. What is specific to collections lives here: which rows are due, how a
 * collection and its product lines become a request, and that a collection's product lines share its status. How a run
 * decides each record's fate is the shared [BatchSyncRunner].
 */
class CboSyncProcessor @Inject constructor(
    private val collectionDao: CboCollectionDao,
    private val productLineDao: ProductLineDao,
    private val api: SyncApiService
) {
    private val runner = BatchSyncRunner(
        store = CollectionStore(),
        idOf = { it.id },
        retryCountOf = { it.retryCount },
        send = { batch ->
            val linesByCollection = productLineDao.getForCollections(batch.map { it.id }).groupBy { it.collectionId }
            api.syncCboCollections(CboSyncRequest(batch.map { it.toSyncDto(linesByCollection[it.id].orEmpty()) }))
        }
    )

    suspend fun syncPending(now: () -> Long = System::currentTimeMillis): SyncRunResult = runner.run(now)

    private inner class CollectionStore : SyncStore<CboCollectionEntity> {
        override suspend fun getSyncable(maxRetries: Int) = collectionDao.getSyncable(maxRetries)

        override suspend fun markSynced(ids: List<String>, now: Long) {
            collectionDao.markSynced(ids, now)
            productLineDao.setStatusForCollections(ids, SyncStatus.SYNCED, now)
        }

        override suspend fun markFailed(ids: List<String>, errorCode: String?, now: Long) {
            collectionDao.markFailed(ids, errorCode, now)
            productLineDao.setStatusForCollections(ids, SyncStatus.FAILED, now)
        }

        override suspend fun markRejected(ids: List<String>, errorCode: String?, maxRetries: Int, now: Long) {
            collectionDao.markRejected(ids, errorCode, maxRetries, now)
            productLineDao.setStatusForCollections(ids, SyncStatus.FAILED, now)
        }
    }

    companion object {
        /** See [SyncPolicy]. */
        const val MAX_RETRIES = SyncPolicy.MAX_RETRIES
        const val BATCH_SIZE = SyncPolicy.BATCH_SIZE
    }
}
