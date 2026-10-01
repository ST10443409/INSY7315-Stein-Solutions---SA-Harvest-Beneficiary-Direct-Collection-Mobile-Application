package com.example.client.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.client.data.local.entity.ProductLineEntity
import com.example.client.data.local.entity.SyncStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface ProductLineDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(productLine: ProductLineEntity)

    @Update
    suspend fun update(productLine: ProductLineEntity)

    @Query("SELECT * FROM product_lines WHERE syncStatus = :status")
    fun getBySyncStatus(status: SyncStatus): Flow<List<ProductLineEntity>>
    
    @Query("SELECT * FROM product_lines WHERE collectionId IN (:collectionIds)")
    suspend fun getForCollections(collectionIds: List<String>): List<ProductLineEntity>

    @Query("UPDATE product_lines SET syncStatus = :status, updatedAt = :now WHERE collectionId IN (:collectionIds)")
    suspend fun setStatusForCollections(collectionIds: List<String>, status: SyncStatus, now: Long)

    @Query("SELECT * FROM product_lines WHERE collectionId = :collectionId")
    fun getByCollectionId(collectionId: String): Flow<List<ProductLineEntity>>
}
