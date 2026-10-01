package com.example.client.sync

import com.example.client.data.local.dao.CboCollectionDao
import com.example.client.data.local.dao.ProductLineDao
import com.example.client.data.local.entity.CboCollectionEntity
import com.example.client.data.local.entity.SyncStatus
import com.example.client.network.CboSyncRecordResult
import com.example.client.network.CboSyncRequest
import com.example.client.network.SyncApiService
import com.example.client.network.toSyncDto
import java.io.IOException
import javax.inject.Inject

/**
 * The sync logic for CBO collections, kept free of WorkManager so it can be unit tested.
 *
 * Status is tracked per record, from what the server says about that record:
 * - success (including "already received"): SYNCED.
 * - failure the server marks `retryable: false` (e.g. VALIDATION_FAILED, DUPLICATE_DETECTED): resending the same
 *   data cannot help, so the record is FAILED with its retries used up and is never sent again. The error code is
 *   kept so the UI can say why.
 * - failure marked `retryable: true`, or a record the server did not mention: FAILED with one retry used; it is
 *   sent again on a later run, until [MAX_RETRIES] is reached.
 *
 * If the server can't be reached, or answers with a transient error, nothing is marked FAILED: the records stay
 * as they were and the run is retried later.
 */
class CboSyncProcessor @Inject constructor(
    private val collectionDao: CboCollectionDao,
    private val productLineDao: ProductLineDao,
    private val api: SyncApiService
) {
    /**
     * Sends everything that is due. Returns [SyncRunResult.RETRY_LATER] when something is worth sending again soon
     * (the server was unreachable, or a record failed in a way that may heal and still has retries left), so the
     * worker's backoff retries it instead of waiting for the next periodic run; otherwise [SyncRunResult.DONE].
     */
    suspend fun syncPending(now: () -> Long = System::currentTimeMillis): SyncRunResult {
        var retryWorthwhile = false
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
                markFailed(ids, null, now())
                continue
            }

            val results = response.body()?.data?.results
            if (results == null) {
                markFailed(ids, null, now())
                retryWorthwhile = retryWorthwhile || batch.any { it.hasRetriesLeftAfterFailing() }
                continue
            }
            val resultById = results.filter { it.clientId in ids }.associateBy { it.clientId }

            val synced = mutableListOf<String>()
            val rejected = mutableListOf<Pair<String, String?>>() // id to error code
            val failed = mutableListOf<CboCollectionEntity>()
            for (record in batch) {
                val result: CboSyncRecordResult? = resultById[record.id]
                when {
                    result?.success == true -> synced += record.id
                    // Not confirmed as received, and the server says resending will not change that.
                    result != null && !result.retryable -> rejected += record.id to result.errorCode
                    // Retryable failure, or the server did not mention the record: try again later.
                    else -> failed += record
                }
            }

            if (synced.isNotEmpty()) markSynced(synced, now())
            for ((errorCode, group) in rejected.groupBy({ it.second }, { it.first })) {
                markRejected(group, errorCode, now())
            }
            // The server's code for each record, so the UI can still say what went wrong.
            for ((errorCode, group) in failed.groupBy({ resultById[it.id]?.errorCode }, { it })) {
                markFailed(group.map { it.id }, errorCode, now())
            }
            retryWorthwhile = retryWorthwhile || failed.any { it.hasRetriesLeftAfterFailing() }
        }
        return if (retryWorthwhile) SyncRunResult.RETRY_LATER else SyncRunResult.DONE
    }

    private suspend fun markSynced(ids: List<String>, now: Long) {
        collectionDao.markSynced(ids, now)
        productLineDao.setStatusForCollections(ids, SyncStatus.SYNCED, now)
    }

    private suspend fun markFailed(ids: List<String>, errorCode: String?, now: Long) {
        collectionDao.markFailed(ids, errorCode, now)
        productLineDao.setStatusForCollections(ids, SyncStatus.FAILED, now)
    }

    private suspend fun markRejected(ids: List<String>, errorCode: String?, now: Long) {
        collectionDao.markRejected(ids, errorCode, MAX_RETRIES, now)
        productLineDao.setStatusForCollections(ids, SyncStatus.FAILED, now)
    }

    // The failure being recorded uses up one retry; is there still one left for a later run?
    private fun CboCollectionEntity.hasRetriesLeftAfterFailing() = retryCount + 1 < MAX_RETRIES

    // Not the record's fault: not signed in, throttled, or the server having a bad moment.
    private fun Int.isTransient() = this == 401 || this == 403 || this == 408 || this == 429 || this >= 500

    companion object {
        /** A FAILED record is retried on later runs until it has failed this many times. */
        const val MAX_RETRIES = 5
        const val BATCH_SIZE = 50
    }
}
