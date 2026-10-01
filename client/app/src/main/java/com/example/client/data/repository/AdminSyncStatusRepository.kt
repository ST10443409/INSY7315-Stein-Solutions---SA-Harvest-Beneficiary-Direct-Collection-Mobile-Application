package com.example.client.data.repository

import com.example.client.data.parseIsoInstantMillis
import com.example.client.network.AdminApiService
import com.example.client.network.FormSyncCountsDto
import com.google.gson.JsonParseException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** One form's records by state, as the server counts them. */
data class FormSyncCounts(
    val total: Int,
    val waiting: Int,
    val retrying: Int,
    val needsAttention: Int,
    val forwarded: Int,
    val duplicates: Int,
    val superseded: Int,
    val dismissed: Int
)

/** What the server reported, and when it counted. */
data class SyncStatusSnapshot(
    val cboCollections: FormSyncCounts,
    val vettingDecisions: FormSyncCounts,
    /** When the server took the counts (or, if it did not say, when this device received them). */
    val takenAtMillis: Long
)

sealed interface SyncStatusResult {
    data class Loaded(val snapshot: SyncStatusSnapshot) : SyncStatusResult

    /** No connection. The monitor needs the server, so there is nothing to show but what it had before. */
    object Offline : SyncStatusResult

    /** The server refused this account (not an Admin, or the session ended). */
    object Denied : SyncStatusResult

    /** Anything else (server error, bad response). */
    object Failed : SyncStatusResult
}

/**
 * The Admin's view of what has reached the server and Foodspace. Unlike the forms this is not offline-first: the counts
 * live on the server, so a failure leaves the screen with whatever it last loaded.
 */
interface AdminSyncStatusRepository {
    suspend fun load(now: () -> Long = System::currentTimeMillis): SyncStatusResult
}

@Singleton
class AdminSyncStatusRepositoryImpl @Inject constructor(
    private val api: AdminApiService
) : AdminSyncStatusRepository {

    override suspend fun load(now: () -> Long): SyncStatusResult {
        val response = try {
            api.getSyncStatus()
        } catch (e: IOException) {
            return SyncStatusResult.Offline
        } catch (e: JsonParseException) {
            return SyncStatusResult.Failed
        }
        if (!response.isSuccessful) {
            return if (response.code() == 401 || response.code() == 403) SyncStatusResult.Denied else SyncStatusResult.Failed
        }
        val data = response.body()?.data ?: return SyncStatusResult.Failed
        return SyncStatusResult.Loaded(
            SyncStatusSnapshot(
                cboCollections = data.cboCollections.toModel(),
                vettingDecisions = data.vettingDecisions.toModel(),
                takenAtMillis = parseIsoInstantMillis(data.generatedAt) ?: now()
            )
        )
    }

    private fun FormSyncCountsDto.toModel() = FormSyncCounts(total, waiting, retrying, needsAttention, forwarded, duplicates, superseded, dismissed)
}
