package com.example.client.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.example.client.data.local.entity.CboCollectionEntity
import com.example.client.data.local.entity.ProductLineEntity
import com.example.client.data.local.entity.SyncStatus
import kotlinx.coroutines.flow.Flow

@Dao
abstract class CboCollectionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insert(collection: CboCollectionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertProductLines(productLines: List<ProductLineEntity>)

    /** Writes a collection and its product lines atomically, so one is never stored without the other. */
    @Transaction
    open suspend fun insertWithProductLines(
        collection: CboCollectionEntity,
        productLines: List<ProductLineEntity>
    ) {
        insert(collection)
        insertProductLines(productLines)
    }

    @Update
    abstract suspend fun update(collection: CboCollectionEntity)

    @Query("SELECT * FROM cbo_collections WHERE syncStatus = :status")
    abstract fun getBySyncStatus(status: SyncStatus): Flow<List<CboCollectionEntity>>

    @Query("SELECT * FROM cbo_collections")
    abstract fun getAll(): Flow<List<CboCollectionEntity>>

    /** All locally stored collections, most recent submission first. */
    @Query("SELECT * FROM cbo_collections ORDER BY createdAt DESC")
    abstract fun observeAllNewestFirst(): Flow<List<CboCollectionEntity>>

    /** Records the sync worker should send: PENDING ones, plus FAILED ones that still have retries left. */
    @Query(
        "SELECT * FROM cbo_collections WHERE syncStatus = 'PENDING' " +
            "OR (syncStatus = 'FAILED' AND retryCount < :maxRetries) ORDER BY createdAt ASC"
    )
    abstract suspend fun getSyncable(maxRetries: Int): List<CboCollectionEntity>

    @Query("UPDATE cbo_collections SET syncStatus = 'SYNCED', updatedAt = :now WHERE id IN (:ids)")
    abstract suspend fun markSynced(ids: List<String>, now: Long)

    @Query(
        "UPDATE cbo_collections SET syncStatus = 'FAILED', retryCount = retryCount + 1, " +
            "updatedAt = :now WHERE id IN (:ids)"
    )
    abstract suspend fun markFailed(ids: List<String>, now: Long)

    @Query("SELECT COUNT(*) FROM cbo_collections WHERE syncStatus = :status")
    abstract fun observeCountByStatus(status: SyncStatus): Flow<Int>
}
