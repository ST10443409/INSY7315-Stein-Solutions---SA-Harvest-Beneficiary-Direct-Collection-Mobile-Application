package com.example.client.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.client.data.local.entity.CboCollectionEntity
import com.example.client.data.local.entity.SyncStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface CboCollectionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(collection: CboCollectionEntity)

    @Update
    suspend fun update(collection: CboCollectionEntity)

    @Query("SELECT * FROM cbo_collections WHERE syncStatus = :status")
    fun getBySyncStatus(status: SyncStatus): Flow<List<CboCollectionEntity>>
    
    @Query("SELECT * FROM cbo_collections")
    fun getAll(): Flow<List<CboCollectionEntity>>
}
