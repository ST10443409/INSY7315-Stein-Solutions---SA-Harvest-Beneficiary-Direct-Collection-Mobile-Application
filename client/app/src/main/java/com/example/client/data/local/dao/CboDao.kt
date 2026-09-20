package com.example.client.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.client.data.local.entity.CboEntity
import com.example.client.data.local.entity.SyncStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface CboDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(cbo: CboEntity)

    @Update
    suspend fun update(cbo: CboEntity)

    @Query("SELECT * FROM cbos WHERE syncStatus = :status")
    fun getBySyncStatus(status: SyncStatus): Flow<List<CboEntity>>
    
    @Query("SELECT * FROM cbos")
    fun getAll(): Flow<List<CboEntity>>
}
