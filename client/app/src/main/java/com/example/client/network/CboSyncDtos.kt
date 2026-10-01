package com.example.client.network

import com.example.client.data.local.entity.CboCollectionEntity
import com.example.client.data.local.entity.ProductLineEntity

// Contract for `POST /api/cbo-collection/sync` (backend #36, CboCollectionController). Field names match
// the Room entities (camelCase JSON).

data class ProductLineSyncDto(
    val id: String,
    val collectionId: String,
    val category: String,
    val kg: String,
    val notes: String?,
    val createdAt: Long,
    val updatedAt: Long
)

data class CboCollectionSyncDto(
    val id: String,
    val cboId: String,
    val arrivalTime: String,
    val departureTime: String?,
    val donorName: String,
    val donorSigned: Boolean,
    val cboSigned: Boolean,
    val deliveryNote: String,
    val noteAttached: Boolean,
    val collectNotes: String,
    val shots: List<Boolean>,
    val latitude: Double?,
    val longitude: Double?,
    val createdAt: Long,
    val updatedAt: Long,
    val productLines: List<ProductLineSyncDto>
)

data class CboSyncRequest(val records: List<CboCollectionSyncDto>)

/**
 * Outcome for one record; the server reports each record separately so one bad record cannot fail a batch.
 * [retryable] is false for validation failures (resending the same data will never work).
 */
data class CboSyncRecordResult(
    val clientId: String?,
    val success: Boolean,
    val alreadyReceived: Boolean = false,
    val error: String? = null,
    val errorCode: String? = null,
    val retryable: Boolean = false
)

data class CboSyncResponse(val results: List<CboSyncRecordResult> = emptyList())

/** Values of [CboSyncRecordResult.errorCode] the app reacts to (see the backend's CboSyncErrorCodes). */
object CboSyncErrorCodes {
    /** The record is invalid as sent; resending the same data will never work. */
    const val VALIDATION_FAILED = "VALIDATION_FAILED"

    /** Another submission already covers this real-world collection; the server kept this one for Admin review. */
    const val DUPLICATE_DETECTED = "DUPLICATE_DETECTED"
}

/** The backend's standard response envelope (`{ success, data, error }`). */
data class ApiEnvelope<T>(val success: Boolean = false, val data: T? = null, val error: ApiErrorBody? = null)

data class ApiErrorBody(val code: String? = null, val message: String? = null)

fun CboCollectionEntity.toSyncDto(lines: List<ProductLineEntity>) = CboCollectionSyncDto(
    id = id,
    cboId = cboId,
    arrivalTime = arrivalTime,
    departureTime = departureTime,
    donorName = donorName,
    donorSigned = donorSigned,
    cboSigned = cboSigned,
    deliveryNote = deliveryNote,
    noteAttached = noteAttached,
    collectNotes = collectNotes,
    shots = shots,
    latitude = latitude,
    longitude = longitude,
    createdAt = createdAt,
    updatedAt = updatedAt,
    productLines = lines.map { ProductLineSyncDto(it.id, it.collectionId, it.category, it.kg, it.notes, it.createdAt, it.updatedAt) }
)
