package com.example.client.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "product_lines")
data class ProductLineEntity(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    
    // Foreign key reference to CboCollectionEntity
    val collectionId: String,
    
    val category: String,
    val kg: String, // String (numeric) as per requirement
    val notes: String?,
    
    // Offline-first bookkeeping fields
    val syncStatus: SyncStatus = SyncStatus.PENDING,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
