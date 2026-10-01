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
    // Number of failed sync attempts for this decision. At or above the sync engine's MAX_RETRIES it is no longer retried.
    val retryCount: Int = 0,
    // Error code the server gave for the last failed attempt (e.g. VALIDATION_FAILED), so the UI can say why.
    // Null while the decision is pending or synced, and when the failure had no code (e.g. no usable response).
    val syncErrorCode: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
