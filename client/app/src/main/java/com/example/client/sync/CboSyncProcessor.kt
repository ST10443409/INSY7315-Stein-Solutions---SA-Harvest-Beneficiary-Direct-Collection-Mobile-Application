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
    /**
     * Sends [author]'s waiting collections, and only theirs (#70): the request carries the signed-in user's token and the
     * server attributes every record in it to that user, so another user's records stay as they are until their author
     * signs in again.
     */
    suspend fun syncPending(author: String, now: () -> Long = System::currentTimeMillis): SyncRunResult =
        BatchSyncRunner(
            store = CollectionStore(author),
            idOf = { it.id },
            retryCountOf = { it.retryCount },
            send = { batch ->
                val linesByCollection = productLineDao.getForCollections(batch.map { it.id }).groupBy { it.collectionId }
                api.syncCboCollections(CboSyncRequest(batch.map { it.toSyncDto(linesByCollection[it.id].orEmpty()) }))
            }
        ).run(now)

    private inner class CollectionStore(private val author: String) : SyncStore<CboCollectionEntity> {
        override suspend fun getSyncable(maxRetries: Int) = collectionDao.getSyncable(maxRetries, author)

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
