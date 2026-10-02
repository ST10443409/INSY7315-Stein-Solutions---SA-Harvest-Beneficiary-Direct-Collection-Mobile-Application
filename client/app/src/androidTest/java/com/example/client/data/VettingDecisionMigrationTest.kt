package com.example.client.data

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Replays the exported schemas for the v3 to v4 upgrade (the vetting sync bookkeeping): a decision an officer saved
 * offline before the app was updated must still be there, and still waiting to be sent, afterwards.
 */
@RunWith(AndroidJUnit4::class)
class VettingDecisionMigrationTest {

    private val dbName = "vetting-migration-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @After
    fun deleteDb() {
        ApplicationProvider.getApplicationContext<Context>().deleteDatabase(dbName)
    }

    private fun insertV3Decision(db: SupportSQLiteDatabase, id: String, status: String) {
        db.execSQL(
            "INSERT INTO vetting_decisions (id, foodspaceRecordId, outcome, notes, officerId, decisionTimestamp, syncStatus, createdAt, updatedAt) " +
                "VALUES ('$id', 'fs-1', 'APPROVE', 'looks fine', 'vetting_test_user', 1700000000000, '$status', 1700000000000, 1700000000000)"
        )
    }

    @Test
    fun migrate3To4_addsTheDecisionSyncBookkeeping_andKeepsExistingDecisions() {
        helper.createDatabase(dbName, 3).apply {
            insertV3Decision(this, "pending-1", "PENDING")
            insertV3Decision(this, "synced-1", "SYNCED")
            close()
        }

        // Also validates the migrated schema against the exported 4.json.
        val db = helper.runMigrationsAndValidate(dbName, 4, true, MIGRATION_3_4)

        db.query("SELECT id, syncStatus, retryCount, syncErrorCode, notes FROM vetting_decisions ORDER BY id").use { c ->
            assertEquals(2, c.count)
            c.moveToNext()
            assertEquals("pending-1", c.getString(0))
            assertEquals("PENDING", c.getString(1))
            assertEquals(0, c.getInt(2)) // it has never failed a sync
            assertNull(c.getString(3))
            assertEquals("looks fine", c.getString(4))
            c.moveToNext()
            assertEquals("synced-1", c.getString(0))
            assertEquals("SYNCED", c.getString(1))
        }
    }

    @Test
    fun aDecisionSavedBeforeTheUpdate_isStillSentAfterIt() = runBlocking {
        helper.createDatabase(dbName, 3).apply {
            insertV3Decision(this, "pending-1", "PENDING")
            close()
        }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(*ALL_MIGRATIONS)
            .build()
        try {
            val syncable = database.vettingDecisionDao().getSyncable(maxRetries = 5, officer = "vetting_test_user")

            assertEquals(listOf("pending-1"), syncable.map { it.id })
            assertEquals(0, syncable.single().retryCount)
        } finally {
            database.close()
        }
    }

    @Test
    fun aDeviceStillOnVersion2_reachesVersion4_withoutLosingItsRecords() {
        helper.createDatabase(dbName, 2).apply {
            execSQL(
                "INSERT INTO cbo_collections (id, cboId, arrivalTime, departureTime, donorName, donorSigned, cboSigned, " +
                    "deliveryNote, noteAttached, collectNotes, shots, latitude, longitude, syncStatus, retryCount, createdAt, updatedAt) " +
                    "VALUES ('c1', 'cbo-1', '09:00', NULL, 'Jane Donor', 1, 1, 'DN-1', 0, '', 'true', NULL, NULL, 'PENDING', 0, 1, 1)"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(dbName, 4, true, MIGRATION_2_3, MIGRATION_3_4)

        db.query("SELECT id FROM cbo_collections").use { c ->
            assertEquals(1, c.count)
            c.moveToNext()
            assertEquals("c1", c.getString(0))
        }
    }
}
