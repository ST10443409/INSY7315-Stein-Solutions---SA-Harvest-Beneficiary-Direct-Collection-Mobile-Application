package com.example.client.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "vetting_decisions")
data class VettingDecision(
    @PrimaryKey
    val id: String, // Client-generated UUID
    
    val foodspaceRecordId: String, // Foreign key in spirit to FoodspaceBeneficiaryRecord
    
    val outcome: DecisionOutcome,
    val notes: String?,
    val officerId: String, // String username as requested
    
    val decisionTimestamp: Long,
    
    // Offline-first sync bookkeeping
    val syncStatus: SyncStatus = SyncStatus.PENDING,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
