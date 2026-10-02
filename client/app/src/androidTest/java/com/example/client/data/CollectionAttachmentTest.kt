package com.example.client.data

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.client.data.local.entity.AttachmentKind
import com.example.client.data.local.entity.CboCollectionEntity
import com.example.client.data.local.entity.CollectionAttachmentEntity
import com.example.client.data.local.entity.ProductLineEntity
import com.example.client.data.local.entity.SyncStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The signatures and photos stored with a collection, against a real Room database: the v4 to v5 upgrade that creates
 * their table, and writing them together with the collection.
 */
@RunWith(AndroidJUnit4::class)
class CollectionAttachmentTest {

    private val dbName = "attachment-migration-test"

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

    private fun collection(id: String) = CboCollectionEntity(
        id = id, cboId = "cbo-1", arrivalTime = "09:00", departureTime = null, donorName = "Donor $id",
        donorSigned = true, cboSigned = true, deliveryNote = "", noteAttached = false, collectNotes = "",
        shots = listOf(true), latitude = null, longitude = null
    )

    private fun attachment(id: String, collectionId: String, kind: AttachmentKind, slot: Int = 0) = CollectionAttachmentEntity(
        id = id, collectionId = collectionId, kind = kind, slot = slot, filePath = "/files/$id", mimeType = "image/png", sizeBytes = 10
    )

    @Test
    fun migrate4To5_createsTheAttachmentsTable_andKeepsExistingCollections() {
        helper.createDatabase(dbName, 4).apply {
            execSQL(
                "INSERT INTO cbo_collections (id, cboId, arrivalTime, departureTime, donorName, donorSigned, cboSigned, deliveryNote, " +
                    "noteAttached, collectNotes, shots, latitude, longitude, syncStatus, retryCount, syncErrorCode, createdAt, updatedAt) " +
                    "VALUES ('old-1', 'cbo-1', '09:00', NULL, 'Jane Donor', 1, 1, '', 0, '', 'true', NULL, NULL, 'PENDING', 0, NULL, 1700000000000, 1700000000000)"
            )
            close()
        }

        // Also validates the migrated schema against the exported 5.json.
        val db = helper.runMigrationsAndValidate(dbName, 5, true, MIGRATION_4_5)

        db.query("SELECT id, donorName, syncStatus FROM cbo_collections").use { c ->
            assertEquals(1, c.count)
            c.moveToNext()
            assertEquals("old-1", c.getString(0))
            assertEquals("Jane Donor", c.getString(1))
            assertEquals("PENDING", c.getString(2)) // still waiting to be sent
        }
        db.query("SELECT COUNT(*) FROM collection_attachments").use { c ->
            c.moveToNext()
            assertEquals(0, c.getInt(0))
        }
    }

    @Test
    fun aCollection_isSavedWithItsLinesAndAttachments_inOneStep() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).build()
        try {
            val dao = db.cboCollectionDao()
            dao.insertWithChildren(
                collection("c1"),
                listOf(ProductLineEntity(collectionId = "c1", category = "Fruit", kg = "5", notes = null)),
                listOf(
                    attachment("sig-d", "c1", AttachmentKind.DONOR_SIGNATURE),
                    attachment("sig-c", "c1", AttachmentKind.CBO_SIGNATURE),
                    attachment("photo-0", "c1", AttachmentKind.PHOTO, slot = 0)
                )
            )
            dao.insertWithChildren(collection("c2"), emptyList(), listOf(attachment("note", "c2", AttachmentKind.DELIVERY_NOTE)))

            val forC1 = dao.getAttachmentsForCollections(listOf("c1"))
            assertEquals(setOf("sig-d", "sig-c", "photo-0"), forC1.map { it.id }.toSet())
            assertTrue(forC1.all { it.syncStatus == SyncStatus.PENDING })
            assertEquals(AttachmentKind.PHOTO, forC1.first { it.id == "photo-0" }.kind)
            assertEquals(1, dao.getAttachmentsForCollections(listOf("c2")).size)
            assertEquals(4, dao.observeAttachments().first().size)
            assertEquals(2, dao.getAll().first().size)
        } finally {
            db.close()
        }
    }

    @Test
    fun aCollectionWithNoPictures_isSavedWithoutAttachments() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).build()
        try {
            db.cboCollectionDao().insertWithChildren(collection("c1"), emptyList(), emptyList())

            assertTrue(db.cboCollectionDao().observeAttachments().first().isEmpty())
            assertEquals(1, db.cboCollectionDao().getAll().first().size)
        } finally {
            db.close()
        }
    }

    @Test
    fun savingTheSameAttachmentAgain_replacesItInsteadOfDuplicatingIt() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).build()
        try {
            val dao = db.cboCollectionDao()
            dao.insertAttachments(listOf(attachment("a", "c1", AttachmentKind.PHOTO)))
            dao.insertAttachments(listOf(attachment("a", "c1", AttachmentKind.PHOTO).copy(filePath = "/files/new")))

            val stored = dao.observeAttachments().first()
            assertEquals(1, stored.size)
            assertEquals("/files/new", stored.single().filePath)
        } finally {
            db.close()
        }
    }
}
