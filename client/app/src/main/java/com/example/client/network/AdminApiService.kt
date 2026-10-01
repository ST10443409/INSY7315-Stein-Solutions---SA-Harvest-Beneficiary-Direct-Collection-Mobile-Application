package com.example.client.network

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

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
    val superseded: Int = 0,
    val dismissed: Int = 0
)

/** `GET /api/admin/sync-status`. [generatedAt] is ISO-8601, when the server took the counts. */
data class AdminSyncStatusDto(
    val cboCollections: FormSyncCountsDto = FormSyncCountsDto(),
    val vettingDecisions: FormSyncCountsDto = FormSyncCountsDto(),
    val generatedAt: String? = null
)

// Failed-sync resolution (backend #50). [form] is CBO_COLLECTION or VETTING_DECISION and [state] a server SyncState name
// (NEEDS_ATTENTION, DUPLICATE_HELD, ...); both stay text here and are read into enums by the repository, so a value a newer
// server adds cannot break parsing. Timestamps are ISO-8601.

data class SyncAttentionItemDto(
    val id: String = "",
    val form: String = "",
    val state: String = "",
    val label: String = "",
    val receivedAt: String? = null,
    val submittedBy: String? = null,
    val syncAttempts: Int = 0,
    val lastAttemptAt: String? = null,
    val error: String? = null
)

data class SyncAttentionPageDto(
    val items: List<SyncAttentionItemDto> = emptyList(),
    val page: Int = 1,
    val pageSize: Int = 0,
    val totalCount: Int = 0,
    val hasMore: Boolean = false
)

data class DuplicateOfDto(
    val id: String = "",
    val label: String = "",
    val state: String = "",
    val receivedAt: String? = null
)

data class AdminActionEntryDto(
    val at: String? = null,
    val admin: String = "",
    val action: String = "",
    val reason: String? = null,
    val resultStatus: String = ""
)

data class SyncRecordDetailDto(
    val id: String = "",
    val form: String = "",
    val state: String = "",
    val label: String = "",
    val receivedAt: String? = null,
    val submittedBy: String? = null,
    val syncAttempts: Int = 0,
    val lastAttemptAt: String? = null,
    val nextAttemptAt: String? = null,
    val error: String? = null,
    val duplicateOf: DuplicateOfDto? = null,
    val canRetry: Boolean = false,
    val canDismiss: Boolean = false,
    val history: List<AdminActionEntryDto> = emptyList()
)

/** The answer to a retry or a dismissal: the state the record was in, and the record as it is now. */
data class ResolutionDto(val previousState: String = "", val record: SyncRecordDetailDto = SyncRecordDetailDto())

data class DismissRequestDto(val reason: String)

// User activity (backend #51). [form] is CBO_COLLECTION or VETTING_DECISION and [role] a UserRole name; [at] is when the
// work was done (the device's clock) and [receivedAt] when the server heard about it, both ISO-8601.

data class UserActivityItemDto(
    val id: String = "",
    val form: String = "",
    val user: String? = null,
    val role: String? = null,
    val at: String? = null,
    val receivedAt: String? = null,
    val label: String = ""
)

/** One page of activity, newest first. [from] and [to] (`yyyy-MM-dd`) are the dates the server applied, including its default window. */
data class UserActivityPageDto(
    val items: List<UserActivityItemDto> = emptyList(),
    val page: Int = 1,
    val pageSize: Int = 0,
    val totalCount: Int = 0,
    val hasMore: Boolean = false,
    val from: String? = null,
    val to: String? = null
)

/** Admin-only endpoints (backend `AdminController`); any other role gets 403. */
interface AdminApiService {
    @GET("/api/admin/sync-status")
    suspend fun getSyncStatus(): Response<ApiEnvelope<AdminSyncStatusDto>>

    @GET("/api/admin/sync-status/attention")
    suspend fun getAttention(
        @Query("page") page: Int,
        @Query("pageSize") pageSize: Int
    ): Response<ApiEnvelope<SyncAttentionPageDto>>

    @GET("/api/admin/sync-status/{id}")
    suspend fun getSyncRecord(
        @Path("id") id: String,
        @Query("form") form: String
    ): Response<ApiEnvelope<SyncRecordDetailDto>>

    @POST("/api/admin/sync-status/{id}/retry")
    suspend fun retrySync(
        @Path("id") id: String,
        @Query("form") form: String
    ): Response<ApiEnvelope<ResolutionDto>>

    @POST("/api/admin/sync-status/{id}/dismiss")
    suspend fun dismissSync(
        @Path("id") id: String,
        @Query("form") form: String,
        @Body request: DismissRequestDto
    ): Response<ApiEnvelope<ResolutionDto>>

    /** Every filter is optional: a null one is left out of the request. */
    @GET("/api/admin/user-activity")
    suspend fun getUserActivity(
        @Query("user") user: String?,
        @Query("role") role: String?,
        @Query("from") from: String?,
        @Query("to") to: String?,
        @Query("page") page: Int,
        @Query("pageSize") pageSize: Int
    ): Response<ApiEnvelope<UserActivityPageDto>>
}
