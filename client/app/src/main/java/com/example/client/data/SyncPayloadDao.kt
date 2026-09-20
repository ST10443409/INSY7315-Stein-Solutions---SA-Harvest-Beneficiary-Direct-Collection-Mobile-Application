package com.example.client.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface SyncPayloadDao {
    @Query("SELECT * FROM sync_payloads WHERE isSynced = 0")
    suspend fun getUnsyncedPayloads(): List<SyncPayload>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(payload: SyncPayload)

    @Query("UPDATE sync_payloads SET isSynced = 1 WHERE id = :id")
    suspend fun markAsSynced(id: String)
}
