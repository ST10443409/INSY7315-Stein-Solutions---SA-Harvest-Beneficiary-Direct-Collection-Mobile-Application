package com.example.client.data

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.client.data.local.entity.SyncStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Replays the exported schemas (app/schemas) to check that upgrading a device keeps its data:
 * a record saved offline on v2 must still be there, and still waiting to sync, after the app is updated to v3.
 */
@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {

    private val dbName = "migration-test"

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

    private fun insertV2Collection(db: SupportSQLiteDatabase, id: String, status: String, retryCount: Int) {
        db.execSQL(
            "INSERT INTO cbo_collections (id, cboId, arrivalTime, departureTime, donorName, donorSigned, cboSigned, " +
                "deliveryNote, noteAttached, collectNotes, shots, latitude, longitude, syncStatus, retryCount, createdAt, updatedAt) " +
                "VALUES ('$id', 'cbo-1', '09:00', NULL, 'Jane Donor', 1, 1, 'DN-1', 0, '', 'true,false,false,false', " +
                "NULL, NULL, '$status', $retryCount, 1700000000000, 1700000000000)"
        )
    }

    @Test
    fun migrate2To3_addsTheErrorCodeColumn_andKeepsExistingRecords() {
        helper.createDatabase(dbName, 2).apply {
            insertV2Collection(this, "pending-1", "PENDING", 0)
            insertV2Collection(this, "failed-1", "FAILED", 2)
            close()
        }

        // Also validates the migrated schema against the exported 3.json.
        val db = helper.runMigrationsAndValidate(dbName, 3, true, MIGRATION_2_3)

        db.query("SELECT id, syncStatus, retryCount, syncErrorCode, donorName FROM cbo_collections ORDER BY id").use { c ->
            assertEquals(2, c.count)
            c.moveToNext()
            assertEquals("failed-1", c.getString(0))
            assertEquals("FAILED", c.getString(1))
            assertEquals(2, c.getInt(2))
            assertNull(c.getString(3)) // nothing was rejected before the column existed
            assertEquals("Jane Donor", c.getString(4))
            c.moveToNext()
            assertEquals("pending-1", c.getString(0))
            assertEquals("PENDING", c.getString(1))
        }
    }

    @Test
    fun aMigratedDatabase_opensThroughRoom_andTheRecordsStillSync() = runBlocking {
        helper.createDatabase(dbName, 2).apply {
            insertV2Collection(this, "pending-1", "PENDING", 0)
            close()
        }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(MIGRATION_2_3, MIGRATION_3_4)
            .build()
        try {
            val syncable = database.cboCollectionDao().getSyncable(maxRetries = 5)

            assertEquals(listOf("pending-1"), syncable.map { it.id })
            assertEquals(SyncStatus.PENDING, syncable.single().syncStatus)
            assertNull(syncable.single().syncErrorCode)
            assertEquals(1, database.cboCollectionDao().getAll().first().size)
        } finally {
            database.close()
        }
    }
}
