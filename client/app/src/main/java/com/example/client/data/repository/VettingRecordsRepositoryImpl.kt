package com.example.client.data.repository

import android.content.Context
import com.example.client.data.local.dao.FoodspaceBeneficiaryDao
import com.example.client.data.local.entity.FoodspaceBeneficiaryRecord
import com.example.client.data.parseIsoInstantMillis
import com.example.client.network.VettingApiService
import com.google.gson.JsonParseException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Remembers [RecordsMeta] across restarts. Not sensitive (a timestamp and a flag), so plain storage. */
interface RecordsMetaStore {
    fun load(): RecordsMeta?
    fun save(meta: RecordsMeta)
}

@Singleton
class PrefsRecordsMetaStore @Inject constructor(
    @ApplicationContext context: Context
) : RecordsMetaStore {
    private val prefs = context.getSharedPreferences("vetting_records_meta", Context.MODE_PRIVATE)

    override fun load(): RecordsMeta? =
        if (prefs.contains(KEY_FETCHED_AT)) RecordsMeta(prefs.getLong(KEY_FETCHED_AT, 0L), prefs.getBoolean(KEY_STALE, false))
        else null

    override fun save(meta: RecordsMeta) {
        prefs.edit().putLong(KEY_FETCHED_AT, meta.fetchedAtMillis).putBoolean(KEY_STALE, meta.stale).apply()
    }

    private companion object {
        const val KEY_FETCHED_AT = "fetched_at"
        const val KEY_STALE = "stale"
    }
}

@Singleton
class VettingRecordsRepositoryImpl @Inject constructor(
    private val dao: FoodspaceBeneficiaryDao,
    private val api: VettingApiService,
    private val metaStore: RecordsMetaStore
) : VettingRecordsRepository {

    private val _meta = MutableStateFlow(metaStore.load())
    override val meta: StateFlow<RecordsMeta?> = _meta.asStateFlow()

    // One fetch at a time: the screen refreshes on open and the officer can tap Sync, and two overlapping
    // downloads of the whole list would only waste data.
    private val refreshLock = Mutex()

    override fun observeRecords(): Flow<List<FoodspaceBeneficiaryRecord>> = dao.observeAllByName()

    override fun observeRecord(id: String): Flow<FoodspaceBeneficiaryRecord?> = dao.observeById(id)

    override suspend fun refresh(now: () -> Long): RefreshOutcome = refreshLock.withLock {
        val fetched = LinkedHashMap<String, FoodspaceBeneficiaryRecord>() // by id: a record moved between pages is kept once
        var stale = false
        var fetchedAt: String? = null
        var page = 1
        while (true) {
            val response = try {
                api.getRecords(page, PAGE_SIZE)
            } catch (e: IOException) {
                return@withLock RefreshOutcome.OFFLINE
            } catch (e: JsonParseException) {
                return@withLock RefreshOutcome.FAILED
            }
            if (!response.isSuccessful) {
                return@withLock if (response.code() == 503) RefreshOutcome.SERVER_UNAVAILABLE else RefreshOutcome.FAILED
            }
            val data = response.body()?.data ?: return@withLock RefreshOutcome.FAILED

            data.items.forEach { fetched[it.id] = it }
            stale = stale || data.stale
            fetchedAt = data.fetchedAt ?: fetchedAt
            if (!data.hasMore) break
            if (++page > MAX_PAGES) return@withLock RefreshOutcome.FAILED // a runaway list must not loop forever
        }

        // Only now, with the whole list in hand, is the cache touched.
        dao.replaceAll(fetched.values.toList())
        val meta = RecordsMeta(parseIsoInstantMillis(fetchedAt) ?: now(), stale)
        metaStore.save(meta)
        _meta.value = meta
        RefreshOutcome.UPDATED
    }

    companion object {
        /** The most the backend allows per page (#43), so the fewest round trips on a poor connection. */
        const val PAGE_SIZE = 100
        const val MAX_PAGES = 200
    }
}
