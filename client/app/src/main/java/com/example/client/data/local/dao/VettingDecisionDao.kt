package com.example.client.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.client.data.local.entity.SyncStatus
import com.example.client.data.local.entity.VettingDecision
import kotlinx.coroutines.flow.Flow

@Dao
interface VettingDecisionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(decision: VettingDecision)

    @Update
    suspend fun update(decision: VettingDecision)

    @Query("SELECT * FROM vetting_decisions WHERE syncStatus = :status")
    fun getBySyncStatus(status: SyncStatus): Flow<List<VettingDecision>>

    @Query("SELECT * FROM vetting_decisions")
    fun getAll(): Flow<List<VettingDecision>>

    /** Every decision on this device, newest first (the Vetting list shows the latest one per record). */
    @Query("SELECT * FROM vetting_decisions ORDER BY decisionTimestamp DESC, createdAt DESC")
    fun observeAllNewestFirst(): Flow<List<VettingDecision>>

    /** Decisions the sync worker should send: PENDING ones, plus FAILED ones that still have retries left. Oldest first. */
    @Query(
        "SELECT * FROM vetting_decisions WHERE syncStatus = 'PENDING' " +
            "OR (syncStatus = 'FAILED' AND retryCount < :maxRetries) ORDER BY decisionTimestamp ASC, createdAt ASC"
    )
    suspend fun getSyncable(maxRetries: Int): List<VettingDecision>

    @Query("UPDATE vetting_decisions SET syncStatus = 'SYNCED', syncErrorCode = NULL, updatedAt = :now WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<String>, now: Long)

    /** A failure that may heal on its own: uses up one retry, and the decision is sent again on a later run. */
    @Query(
        "UPDATE vetting_decisions SET syncStatus = 'FAILED', retryCount = retryCount + 1, " +
            "syncErrorCode = :errorCode, updatedAt = :now WHERE id IN (:ids)"
    )
    suspend fun markFailed(ids: List<String>, errorCode: String?, now: Long)

    /** A failure resending cannot fix: FAILED with all [maxRetries] used up, so [getSyncable] skips it from now on. */
    @Query(
        "UPDATE vetting_decisions SET syncStatus = 'FAILED', retryCount = :maxRetries, " +
            "syncErrorCode = :errorCode, updatedAt = :now WHERE id IN (:ids)"
    )
    suspend fun markRejected(ids: List<String>, errorCode: String?, maxRetries: Int, now: Long)

    /** The decisions made on one record, newest first. */
    @Query("SELECT * FROM vetting_decisions WHERE foodspaceRecordId = :recordId ORDER BY decisionTimestamp DESC, createdAt DESC")
    fun observeForRecord(recordId: String): Flow<List<VettingDecision>>
}
