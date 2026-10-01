package com.example.client.data.repository

import com.example.client.data.local.entity.FoodspaceBeneficiaryRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** What the last successful fetch of the Vetting records looked like. */
data class RecordsMeta(
    /** When the backend took the list from Foodspace (or, if it did not say, when this device fetched it). */
    val fetchedAtMillis: Long,
    /** The backend could not reach Foodspace and served its last copy, so the list may be out of date. */
    val stale: Boolean
)

enum class RefreshOutcome {
    /** The cache now holds the backend's current list. */
    UPDATED,

    /** No connection. Nothing changed; the cached records are still there. */
    OFFLINE,

    /** The backend has nothing stored and Foodspace is not answering. Nothing changed. */
    SERVER_UNAVAILABLE,

    /** Anything else (signed out, bad response). Nothing changed. */
    FAILED
}

/**
 * The beneficiary records a vetting officer reviews (Form 2). Everything the screens show comes from the local
 * cache, so they work without a connection; [refresh] is the only thing that needs one, and it never touches the
 * cache unless it fetched the whole list successfully.
 */
interface VettingRecordsRepository {
    /** The cached records, A to Z by name. Updates live. */
    fun observeRecords(): Flow<List<FoodspaceBeneficiaryRecord>>

    /** One cached record, or null if it is not (or no longer) in the cache. Updates live. */
    fun observeRecord(id: String): Flow<FoodspaceBeneficiaryRecord?>

    /** Null until a fetch has succeeded on this device. */
    val meta: StateFlow<RecordsMeta?>

    /** Fetches every page from the backend and replaces the cache. Safe to call while another refresh is running. [now] stamps the fetch when the backend gives no time. */
    suspend fun refresh(now: () -> Long = System::currentTimeMillis): RefreshOutcome
}
