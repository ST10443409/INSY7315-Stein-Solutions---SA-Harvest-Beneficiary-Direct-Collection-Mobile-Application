package com.example.client.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.client.data.local.dao.CboCollectionDao
import com.example.client.data.local.entity.CboCollectionEntity
import com.example.client.data.local.entity.SyncStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The sync bookkeeping queries run against a real (in-memory) Room database. The JVM tests use fake DAOs, so this
 * is what proves the SQL behind markFailed / markRejected / markSynced / getSyncable does what the sync logic assumes.
 */
@RunWith(AndroidJUnit4::class)
class CboCollectionDaoSyncTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: CboCollectionDao

    private val maxRetries = 5

    @Before
    fun createDb() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).build()
        dao = db.cboCollectionDao()
    }

    @After
    fun closeDb() = db.close()

    private fun record(id: String, status: SyncStatus = SyncStatus.PENDING, retryCount: Int = 0, code: String? = null) =
        CboCollectionEntity(
            id = id, cboId = "cbo-1", arrivalTime = "09:00", departureTime = null, donorName = "Donor $id",
            donorSigned = true, cboSigned = true, deliveryNote = "", noteAttached = false, collectNotes = "",
            shots = listOf(true), latitude = null, longitude = null,
            syncStatus = status, retryCount = retryCount, syncErrorCode = code
        )

    private suspend fun get(id: String) = dao.getAll().first().first { it.id == id }

    @Test
    fun markFailed_usesUpOneRetry_andKeepsTheErrorCode() = runBlocking {
        dao.insert(record("a"))

        dao.markFailed(listOf("a"), "SERVER_ERROR", now = 10L)

        val a = get("a")
        assertEquals(SyncStatus.FAILED, a.syncStatus)
        assertEquals(1, a.retryCount)
        assertEquals("SERVER_ERROR", a.syncErrorCode)
        assertEquals(10L, a.updatedAt)
    }

    @Test
    fun markRejected_usesUpEveryRetry_soTheRecordIsNeverSentAgain() = runBlocking {
        dao.insert(record("a"))

        dao.markRejected(listOf("a"), "DUPLICATE_DETECTED", maxRetries, now = 10L)

        val a = get("a")
        assertEquals(SyncStatus.FAILED, a.syncStatus)
        assertEquals(maxRetries, a.retryCount)
        assertEquals("DUPLICATE_DETECTED", a.syncErrorCode)
        assertEquals(emptyList<String>(), dao.getSyncable(maxRetries, "tester").map { it.id })
    }

    @Test
    fun markSynced_clearsTheErrorCode() = runBlocking {
        dao.insert(record("a", SyncStatus.FAILED, retryCount = 2, code = "SERVER_ERROR"))

        dao.markSynced(listOf("a"), now = 10L)

        val a = get("a")
        assertEquals(SyncStatus.SYNCED, a.syncStatus)
        assertNull(a.syncErrorCode)
    }

    @Test
    fun getSyncable_returnsPending_andFailedWithRetriesLeft_oldestFirst() = runBlocking {
        dao.insert(record("pending").copy(createdAt = 3))
        dao.insert(record("again", SyncStatus.FAILED, retryCount = 4, code = "SERVER_ERROR").copy(createdAt = 1))
        dao.insert(record("exhausted", SyncStatus.FAILED, retryCount = maxRetries, code = "SERVER_ERROR").copy(createdAt = 2))
        dao.insert(record("rejected", SyncStatus.FAILED, retryCount = maxRetries, code = "VALIDATION_FAILED").copy(createdAt = 2))
        dao.insert(record("done", SyncStatus.SYNCED).copy(createdAt = 0))

        assertEquals(listOf("again", "pending"), dao.getSyncable(maxRetries, "tester").map { it.id })
    }

    // ── #70: who may send a record ────────────────────────────────────────────────────

    @Test
    fun getSyncable_returnsOnlyTheAuthorsOwnRecords_andThoseWithNoRecordedAuthor() = runBlocking {
        dao.insert(record("mine").copy(authorUsername = "collector_one", createdAt = 1))
        dao.insert(record("theirs").copy(authorUsername = "collector_two", createdAt = 2))
        dao.insert(record("legacy").copy(authorUsername = null, createdAt = 3))

        assertEquals(listOf("mine", "legacy"), dao.getSyncable(maxRetries, "collector_one").map { it.id })
        assertEquals(listOf("theirs", "legacy"), dao.getSyncable(maxRetries, "collector_two").map { it.id })
        assertEquals(listOf("legacy"), dao.getSyncable(maxRetries, "nobody_else").map { it.id })
    }

    @Test
    fun theAuthorIsComparedIgnoringCase_asTheServerDoesAtSignIn() = runBlocking {
        dao.insert(record("mine").copy(authorUsername = "Collector_One"))

        assertEquals(listOf("mine"), dao.getSyncable(maxRetries, "collector_one").map { it.id })
        assertEquals(listOf("mine"), dao.observeByAuthorNewestFirst("COLLECTOR_ONE").first().map { it.id })
    }

    @Test
    fun aPersonSeesOnlyTheirOwnRecords_newestFirst() = runBlocking {
        dao.insert(record("old").copy(authorUsername = "collector_one", createdAt = 1))
        dao.insert(record("new").copy(authorUsername = "collector_one", createdAt = 3))
        dao.insert(record("theirs").copy(authorUsername = "collector_two", createdAt = 2))

        assertEquals(listOf("new", "old"), dao.observeByAuthorNewestFirst("collector_one").first().map { it.id })
    }

    @Test
    fun otherAccountsWaitingRecords_areCounted_butNotMineOrSyncedOrExhaustedOnes() = runBlocking {
        dao.insert(record("theirs-pending").copy(authorUsername = "collector_two"))
        dao.insert(record("theirs-retry", SyncStatus.FAILED, retryCount = 2).copy(authorUsername = "collector_two"))
        dao.insert(record("theirs-done", SyncStatus.SYNCED).copy(authorUsername = "collector_two"))
        dao.insert(record("theirs-dead", SyncStatus.FAILED, retryCount = maxRetries).copy(authorUsername = "collector_two"))
        dao.insert(record("mine").copy(authorUsername = "collector_one"))
        dao.insert(record("legacy").copy(authorUsername = null))

        assertEquals(2, dao.observeWaitingForOtherAuthors("collector_one", maxRetries).first())
    }

    @Test
    fun updates_touchOnlyTheGivenIds() = runBlocking {
        dao.insert(record("a"))
        dao.insert(record("b"))

        dao.markRejected(listOf("a"), "VALIDATION_FAILED", maxRetries, now = 10L)

        assertEquals(SyncStatus.PENDING, get("b").syncStatus)
        assertNull(get("b").syncErrorCode)
    }
}
