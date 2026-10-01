package com.example.client.data.repository

import com.example.client.data.local.entity.CboCollectionEntity
import com.example.client.data.local.entity.ProductLineEntity
import com.example.client.data.local.entity.SyncStatus
import kotlinx.coroutines.flow.Flow

/**
 * Local-first store for CBO collections (Form 1).
 *
 * Saving only ever touches the local database: no network call happens here, whatever the
 * connectivity. Syncing is done separately by the background worker, which updates the
 * records' sync status; the flows below re-emit when that happens.
 */
interface CboCollectionRepository {
    /**
     * Saves the collection and its product lines locally with [SyncStatus.PENDING]. The ids on
     * the given entities are kept as-is, so saving the same record again overwrites it rather
     * than creating a duplicate.
     */
    suspend fun save(collection: CboCollectionEntity, productLines: List<ProductLineEntity>)

    /** All locally stored collections, most recent submission first. Updates live. */
    fun observeAll(): Flow<List<CboCollectionEntity>>

    /** Number of locally stored collections currently in [status]. Updates live. */
    fun observeCount(status: SyncStatus): Flow<Int>
}
