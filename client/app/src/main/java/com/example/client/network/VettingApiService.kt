package com.example.client.network

import com.example.client.data.local.entity.FoodspaceBeneficiaryRecord
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * One page of `GET /api/vetting/records` (backend #43). [items] carry exactly the fields of
 * [FoodspaceBeneficiaryRecord]. [stale] is true when the backend could not refresh from Foodspace and served its last
 * copy; [fetchedAt] (ISO-8601) is when that copy was taken.
 */
data class VettingRecordsPageDto(
    val items: List<FoodspaceBeneficiaryRecord> = emptyList(),
    val page: Int = 1,
    val pageSize: Int = 0,
    val totalCount: Int = 0,
    val hasMore: Boolean = false,
    val fetchedAt: String? = null,
    val stale: Boolean = false
)

interface VettingApiService {
    @GET("/api/vetting/records")
    suspend fun getRecords(
        @Query("page") page: Int,
        @Query("pageSize") pageSize: Int
    ): Response<ApiEnvelope<VettingRecordsPageDto>>
}
