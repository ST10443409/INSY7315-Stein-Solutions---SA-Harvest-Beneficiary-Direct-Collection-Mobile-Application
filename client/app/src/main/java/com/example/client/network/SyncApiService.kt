package com.example.client.network

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

data class SyncRequest(val id: String, val data: String)
data class SyncResponse(val status: String, val receivedId: String)

interface SyncApiService {
    @POST("/api/sync")
    suspend fun syncData(@Body request: SyncRequest): Response<SyncResponse>

    @POST("/api/cbo-collection/sync")
    suspend fun syncCboCollections(@Body request: CboSyncRequest): Response<ApiEnvelope<CboSyncResponse>>

    @POST("/api/vetting/sync")
    suspend fun syncVettingDecisions(@Body request: VettingSyncRequest): Response<ApiEnvelope<CboSyncResponse>>
}
