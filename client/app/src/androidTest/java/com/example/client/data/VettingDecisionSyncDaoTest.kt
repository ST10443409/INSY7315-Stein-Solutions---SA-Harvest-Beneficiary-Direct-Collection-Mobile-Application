package com.example.client.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.client.data.local.dao.VettingDecisionDao
import com.example.client.data.local.entity.SyncStatus
import com.example.client.data.local.entity.UNKNOWN_OFFICER
import com.example.client.testing.sampleDecision
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The decision sync bookkeeping queries on a real (in-memory) Room database. The JVM tests use a fake DAO, so this is what
 * proves the SQL does what the sync logic assumes.
 */
@RunWith(AndroidJUnit4::class)
class VettingDecisionSyncDaoTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: VettingDecisionDao

    private val maxRetries = 5

    @Before
    fun createDb() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).build()
        dao = db.vettingDecisionDao()
    }

    @After
    fun closeDb() = db.close()

    private fun decision(id: String, status: SyncStatus = SyncStatus.PENDING, retryCount: Int = 0, code: String? = null, at: Long = 1_000) =
        sampleDecision(recordId = "fs-$id", at = at).copy(id = id, syncStatus = status, retryCount = retryCount, syncErrorCode = code)

    private suspend fun get(id: String) = dao.getAll().first().first { it.id == id }

    @Test
    fun markFailed_usesUpOneRetry_andKeepsTheErrorCode() = runBlocking {
        dao.insert(decision("a"))

        dao.markFailed(listOf("a"), "SERVER_ERROR", now = 10L)

        val a = get("a")
        assertEquals(SyncStatus.FAILED, a.syncStatus)
        assertEquals(1, a.retryCount)
        assertEquals("SERVER_ERROR", a.syncErrorCode)
        assertEquals(10L, a.updatedAt)
    }

    @Test
    fun markRejected_usesUpEveryRetry_soTheDecisionIsNeverSentAgain() = runBlocking {
        dao.insert(decision("a"))

        dao.markRejected(listOf("a"), "VALIDATION_FAILED", maxRetries, now = 10L)

        val a = get("a")
        assertEquals(SyncStatus.FAILED, a.syncStatus)
        assertEquals(maxRetries, a.retryCount)
        assertEquals("VALIDATION_FAILED", a.syncErrorCode)
        assertEquals(emptyList<String>(), dao.getSyncable(maxRetries, "vetting_test_user").map { it.id })
    }

    @Test
    fun markSynced_clearsTheErrorCode() = runBlocking {
        dao.insert(decision("a", SyncStatus.FAILED, retryCount = 2, code = "SERVER_ERROR"))

        dao.markSynced(listOf("a"), now = 10L)

        val a = get("a")
        assertEquals(SyncStatus.SYNCED, a.syncStatus)
        assertNull(a.syncErrorCode)
    }

    // ── #70: who may send a decision ──────────────────────────────────────────────────

    private fun by(officer: String, id: String, at: Long = 1_000, status: SyncStatus = SyncStatus.PENDING) =
        sampleDecision(recordId = "fs-$id", at = at, officer = officer).copy(id = id, syncStatus = status)

    @Test
    fun getSyncable_returnsOnlyTheOfficersOwnDecisions_andThoseWithNoRecordedOfficer() = runBlocking {
        dao.insert(by("officer_one", "mine", at = 1))
        dao.insert(by("officer_two", "theirs", at = 2))
        dao.insert(by(UNKNOWN_OFFICER, "legacy", at = 3))

        assertEquals(listOf("mine", "legacy"), dao.getSyncable(maxRetries, "officer_one").map { it.id })
        assertEquals(listOf("theirs", "legacy"), dao.getSyncable(maxRetries, "officer_two").map { it.id })
    }

    @Test
    fun theOfficerIsComparedIgnoringCase() = runBlocking {
        dao.insert(by("Officer_One", "mine"))

        assertEquals(listOf("mine"), dao.getSyncable(maxRetries, "officer_one").map { it.id })
        assertEquals(listOf("mine"), dao.observeByOfficerNewestFirst("OFFICER_ONE").first().map { it.id })
    }

    @Test
    fun otherOfficersWaitingDecisions_areCounted_butNotMineOrSentOnes() = runBlocking {
        dao.insert(by("officer_two", "theirs"))
        dao.insert(by("officer_two", "theirs-done", status = SyncStatus.SYNCED))
        dao.insert(by("officer_one", "mine"))
        dao.insert(by(UNKNOWN_OFFICER, "legacy"))

        assertEquals(1, dao.observeWaitingForOtherOfficers("officer_one", maxRetries).first())
    }

    @Test
    fun getSyncable_returnsPending_andFailedWithRetriesLeft_oldestFirst() = runBlocking {
        dao.insert(decision("pending", at = 3))
        dao.insert(decision("again", SyncStatus.FAILED, retryCount = 4, code = "SERVER_ERROR", at = 1))
        dao.insert(decision("exhausted", SyncStatus.FAILED, retryCount = maxRetries, code = "SERVER_ERROR", at = 2))
        dao.insert(decision("rejected", SyncStatus.FAILED, retryCount = maxRetries, code = "VALIDATION_FAILED", at = 2))
        dao.insert(decision("done", SyncStatus.SYNCED, at = 0))

        assertEquals(listOf("again", "pending"), dao.getSyncable(maxRetries, "vetting_test_user").map { it.id })
    }

    @Test
    fun updates_touchOnlyTheGivenIds() = runBlocking {
        dao.insert(decision("a"))
        dao.insert(decision("b"))

        dao.markRejected(listOf("a"), "VALIDATION_FAILED", maxRetries, now = 10L)

        assertEquals(SyncStatus.PENDING, get("b").syncStatus)
        assertNull(get("b").syncErrorCode)
    }

    @Test
    fun aNewDecision_startsWithNoRetriesUsedAndNoError() = runBlocking {
        dao.insert(sampleDecision("fs-1"))

        val saved = dao.getAll().first().single()

        assertEquals(0, saved.retryCount)
        assertNull(saved.syncErrorCode)
        assertEquals(SyncStatus.PENDING, saved.syncStatus)
    }
}
