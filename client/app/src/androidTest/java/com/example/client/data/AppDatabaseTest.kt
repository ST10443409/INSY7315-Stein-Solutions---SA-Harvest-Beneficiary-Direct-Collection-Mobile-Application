package com.example.client.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.client.data.local.dao.CboCollectionDao
import com.example.client.data.local.dao.VettingDecisionDao
import com.example.client.data.local.entity.CboCollectionEntity
import com.example.client.data.local.entity.DecisionOutcome
import com.example.client.data.local.entity.SyncStatus
import com.example.client.data.local.entity.VettingDecision
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class AppDatabaseTest {
    private lateinit var db: AppDatabase
    private lateinit var cboCollectionDao: CboCollectionDao
    private lateinit var vettingDecisionDao: VettingDecisionDao

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(
            context, AppDatabase::class.java
        ).build()
        cboCollectionDao = db.cboCollectionDao()
        vettingDecisionDao = db.vettingDecisionDao()
    }

    @After
    @Throws(IOException::class)
    fun closeDb() {
        db.close()
    }

    @Test
    @Throws(Exception::class)
    fun insertAndReadCboCollection() = runBlocking {
        val collection = CboCollectionEntity(
            id = "coll_123",
            cboId = "cbo_1",
            arrivalTime = "10:00 AM",
            departureTime = null,
            donorName = "John Doe",
            donorSigned = true,
            cboSigned = false,
            deliveryNote = "Note-123",
            noteAttached = true,
            collectNotes = "All good",
            shots = listOf(true, false, false, false),
            latitude = -33.92,
            longitude = 18.42,
            syncStatus = SyncStatus.PENDING,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        
        cboCollectionDao.insert(collection)
        val bySyncStatus = cboCollectionDao.getBySyncStatus(SyncStatus.PENDING).first()
        
        assertEquals(1, bySyncStatus.size)
        assertEquals("coll_123", bySyncStatus[0].id)
        assertEquals("John Doe", bySyncStatus[0].donorName)
    }

    @Test
    @Throws(Exception::class)
    fun insertAndReadVettingDecision() = runBlocking {
        val decision = VettingDecision(
            id = "vet_123",
            foodspaceRecordId = "fs_1",
            outcome = DecisionOutcome.APPROVE,
            notes = "Approved by agent",
            officerId = "officer_1",
            decisionTimestamp = System.currentTimeMillis(),
            syncStatus = SyncStatus.PENDING
        )
        
        vettingDecisionDao.insert(decision)
        val bySyncStatus = vettingDecisionDao.getBySyncStatus(SyncStatus.PENDING).first()
        
        assertEquals(1, bySyncStatus.size)
        assertEquals("vet_123", bySyncStatus[0].id)
        assertEquals(DecisionOutcome.APPROVE, bySyncStatus[0].outcome)
    }
}
