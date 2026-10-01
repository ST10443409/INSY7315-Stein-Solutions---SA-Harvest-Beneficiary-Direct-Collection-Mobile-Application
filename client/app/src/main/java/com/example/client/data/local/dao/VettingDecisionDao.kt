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

    /** The decisions made on one record, newest first. */
    @Query("SELECT * FROM vetting_decisions WHERE foodspaceRecordId = :recordId ORDER BY decisionTimestamp DESC, createdAt DESC")
    fun observeForRecord(recordId: String): Flow<List<VettingDecision>>
}
