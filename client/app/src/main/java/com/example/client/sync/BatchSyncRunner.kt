package com.example.client.sync

import com.example.client.network.ApiEnvelope
import com.example.client.network.CboSyncRecordResult
import com.example.client.network.CboSyncResponse
import retrofit2.Response
import java.io.IOException

/** The database operations a sync needs for one kind of record. Each kind (collections, decisions) supplies its own. */
interface SyncStore<T> {
    /** Records to send: PENDING ones, plus FAILED ones that still have retries left. */
    suspend fun getSyncable(maxRetries: Int): List<T>

    /** The server has the records: SYNCED, with any earlier error forgotten. */
    suspend fun markSynced(ids: List<String>, now: Long)

    /** A failure that may heal: FAILED, one retry used up, [errorCode] kept for the UI. */
    suspend fun markFailed(ids: List<String>, errorCode: String?, now: Long)

    /** A failure resending cannot fix: FAILED with all [maxRetries] used up, so it is never sent again. */
    suspend fun markRejected(ids: List<String>, errorCode: String?, maxRetries: Int, now: Long)
}

/** The retry budget every kind of record is held to. */
object SyncPolicy {
    /** A FAILED record is retried on later runs until it has failed this many times. */
    const val MAX_RETRIES = 5
    const val BATCH_SIZE = 50
}

/**
 * The one implementation of "send what is waiting, and record what the server said about each record", shared by
 * every kind of record (CBO collections, vetting decisions), so they cannot drift apart. It is free of WorkManager so it
 * can be unit tested. The wire format is shared too: both endpoints answer with one result per record
 * (see the backend's CBO and Vetting sync endpoints).
 *
 * Status is decided per record, from what the server says about that record:
 * - success (including "already received"): SYNCED.
 * - failure the server marks `retryable: false` (e.g. VALIDATION_FAILED, DUPLICATE_DETECTED): resending the same data
 *   cannot help, so the record is FAILED with its retries used up and is never sent again. The error code is kept so the
 *   UI can say why.
 * - failure marked `retryable: true`, or a record the server did not mention: FAILED with one retry used; it is sent
 *   again on a later run, until [SyncPolicy.MAX_RETRIES] is reached.
 *
 * If the server can't be reached, or answers with a transient error, nothing is marked FAILED: the records stay as they
 * were and the run is retried later.
 */
class BatchSyncRunner<T>(
    private val store: SyncStore<T>,
    private val idOf: (T) -> String,
    private val retryCountOf: (T) -> Int,
    private val send: suspend (batch: List<T>) -> Response<ApiEnvelope<CboSyncResponse>>
) {
    /**
     * Sends everything that is due. Returns [SyncRunResult.RETRY_LATER] when something is worth sending again soon
     * (the server was unreachable, or a record failed in a way that may heal and still has retries left), so the
     * worker's backoff retries it instead of waiting for the next periodic run; otherwise [SyncRunResult.DONE].
     */
    suspend fun run(now: () -> Long): SyncRunResult {
        var retryWorthwhile = false
        val syncable = store.getSyncable(SyncPolicy.MAX_RETRIES)
        for (batch in syncable.chunked(SyncPolicy.BATCH_SIZE)) {
            val ids = batch.map(idOf)

            val response = try {
                send(batch)
            } catch (e: IOException) {
                return SyncRunResult.RETRY_LATER
            }

            if (!response.isSuccessful) {
                if (response.code().isTransient()) return SyncRunResult.RETRY_LATER
                // The server rejected the whole request (e.g. 400): these records won't succeed as sent.
                store.markFailed(ids, null, now())
                continue
            }

            val results = response.body()?.data?.results
            if (results == null) {
                store.markFailed(ids, null, now())
                retryWorthwhile = retryWorthwhile || batch.any { it.hasRetriesLeftAfterFailing() }
                continue
            }
            val resultById = results.filter { it.clientId in ids }.associateBy { it.clientId }

            val synced = mutableListOf<String>()
            val rejected = mutableListOf<Pair<String, String?>>() // id to error code
            val failed = mutableListOf<T>()
            for (record in batch) {
                val result: CboSyncRecordResult? = resultById[idOf(record)]
                when {
                    result?.success == true -> synced += idOf(record)
                    // Not confirmed as received, and the server says resending will not change that.
                    result != null && !result.retryable -> rejected += idOf(record) to result.errorCode
                    // Retryable failure, or the server did not mention the record: try again later.
                    else -> failed += record
                }
            }

            if (synced.isNotEmpty()) store.markSynced(synced, now())
            for ((errorCode, group) in rejected.groupBy({ it.second }, { it.first })) {
                store.markRejected(group, errorCode, SyncPolicy.MAX_RETRIES, now())
            }
            // The server's code for each record, so the UI can still say what went wrong.
            for ((errorCode, group) in failed.groupBy({ resultById[idOf(it)]?.errorCode }, { it })) {
                store.markFailed(group.map(idOf), errorCode, now())
            }
            retryWorthwhile = retryWorthwhile || failed.any { it.hasRetriesLeftAfterFailing() }
        }
        return if (retryWorthwhile) SyncRunResult.RETRY_LATER else SyncRunResult.DONE
    }

    // The failure being recorded uses up one retry; is there still one left for a later run?
    private fun T.hasRetriesLeftAfterFailing() = retryCountOf(this) + 1 < SyncPolicy.MAX_RETRIES

    // Not the record's fault: not signed in, throttled, or the server having a bad moment.
    private fun Int.isTransient() = this == 401 || this == 403 || this == 408 || this == 429 || this >= 500
}
