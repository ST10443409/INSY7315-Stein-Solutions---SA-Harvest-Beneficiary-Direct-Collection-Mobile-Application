package com.example.client.sync

import com.example.client.data.local.dao.CboCollectionDao
import com.example.client.data.local.dao.ProductLineDao
import com.example.client.data.local.entity.SyncStatus
import com.example.client.network.CboSyncRequest
import com.example.client.network.SyncApiService
import com.example.client.network.toSyncDto
import java.io.IOException
import javax.inject.Inject

/**
 * The sync logic for CBO collections, kept free of WorkManager so it can be unit tested.
 *
 * Status is tracked per record: only records the server reports as failed (or does not
 * mention) become FAILED. If the server can't be reached, or answers with a transient error,
 * nothing is marked FAILED: the records stay as they were and the run is retried later.
 */
class CboSyncProcessor @Inject constructor(
    private val collectionDao: CboCollectionDao,
    private val productLineDao: ProductLineDao,
    private val api: SyncApiService
) {
    suspend fun syncPending(now: () -> Long = System::currentTimeMillis): SyncRunResult {
        val syncable = collectionDao.getSyncable(MAX_RETRIES)
        for (batch in syncable.chunked(BATCH_SIZE)) {
            val ids = batch.map { it.id }
            val linesByCollection = productLineDao.getForCollections(ids).groupBy { it.collectionId }
            val request = CboSyncRequest(batch.map { it.toSyncDto(linesByCollection[it.id].orEmpty()) })

            val response = try {
                api.syncCboCollections(request)
            } catch (e: IOException) {
                return SyncRunResult.RETRY_LATER
            }

            if (!response.isSuccessful) {
                if (response.code().isTransient()) return SyncRunResult.RETRY_LATER
                // The server rejected the whole request (e.g. 400): these records won't succeed as sent.
                markFailed(ids, now())
                continue
            }

            val results = response.body()?.data?.results
            if (results == null) {
                markFailed(ids, now())
                continue
            }
            val succeeded = results.filter { it.success }.mapNotNull { it.clientId }.filter { it in ids }.toSet()
            // Anything the server didn't confirm counts as failed.
            val failed = ids.filterNot { it in succeeded }
            if (succeeded.isNotEmpty()) markSynced(succeeded.toList(), now())
            if (failed.isNotEmpty()) markFailed(failed, now())
        }
        return SyncRunResult.DONE
    }

    private suspend fun markSynced(ids: List<String>, now: Long) {
        collectionDao.markSynced(ids, now)
        productLineDao.setStatusForCollections(ids, SyncStatus.SYNCED, now)
    }

    private suspend fun markFailed(ids: List<String>, now: Long) {
        collectionDao.markFailed(ids, now)
        productLineDao.setStatusForCollections(ids, SyncStatus.FAILED, now)
    }

    // Not the record's fault: not signed in, throttled, or the server having a bad moment.
    private fun Int.isTransient() = this == 401 || this == 403 || this == 408 || this == 429 || this >= 500

    companion object {
        /** A FAILED record is retried on later runs until it has failed this many times. */
        const val MAX_RETRIES = 5
        const val BATCH_SIZE = 50
    }
}
