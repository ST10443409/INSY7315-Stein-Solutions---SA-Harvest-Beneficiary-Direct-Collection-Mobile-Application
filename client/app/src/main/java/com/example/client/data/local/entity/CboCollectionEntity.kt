package com.example.client.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "cbo_collections")
data class CboCollectionEntity(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    
    // Foreign key to CBO (conceptually, actual FK relation not required in this sprint)
    val cboId: String,
    
    // Collection Record Fields
    val arrivalTime: String,
    val departureTime: String?,
    val donorName: String,
    val donorSigned: Boolean,
    val cboSigned: Boolean,
    val deliveryNote: String,
    val noteAttached: Boolean,
    val collectNotes: String,
    val shots: List<Boolean>,
    
    // Location / GPS Capture
    val latitude: Double?,
    val longitude: Double?,
    
    // Offline-first bookkeeping fields
    val syncStatus: SyncStatus = SyncStatus.PENDING,
    // Number of failed sync attempts reported by the server for this record. At or above the sync
    // processor's MAX_RETRIES the record is no longer retried automatically.
    val retryCount: Int = 0,
    // Error code the server gave for the last failed attempt (e.g. DUPLICATE_DETECTED), so the UI can say why.
    // Null while the record is pending or synced, and when the failure had no code (e.g. no usable response).
    val syncErrorCode: String? = null,
    // The username of the person who captured this record, and the only one it is ever sent as (#70): the server trusts the
    // token of whoever sends it, so on a shared phone a record sent under another account would be attributed to them.
    // Device-only: never sent (the server reads the submitter from the token). Null only for a record saved before this
    // was kept; such a record is sent by whoever is signed in, as it always was.
    val authorUsername: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
