package com.example.client.network

import retrofit2.Response
import retrofit2.http.GET

/**
 * How many records of one form are in each state on the server (backend #49). The states do not overlap, so they add up to
 * [total]. [duplicates] only applies to Form 1 and [superseded] only to Form 2; the other is always zero.
 */
data class FormSyncCountsDto(
    val total: Int = 0,
    val waiting: Int = 0,
    val retrying: Int = 0,
    val needsAttention: Int = 0,
    val forwarded: Int = 0,
    val duplicates: Int = 0,
    val superseded: Int = 0
)

/** `GET /api/admin/sync-status`. [generatedAt] is ISO-8601, when the server took the counts. */
data class AdminSyncStatusDto(
    val cboCollections: FormSyncCountsDto = FormSyncCountsDto(),
    val vettingDecisions: FormSyncCountsDto = FormSyncCountsDto(),
    val generatedAt: String? = null
)

/** Admin-only endpoints (backend `AdminController`); any other role gets 403. */
interface AdminApiService {
    @GET("/api/admin/sync-status")
    suspend fun getSyncStatus(): Response<ApiEnvelope<AdminSyncStatusDto>>
}
