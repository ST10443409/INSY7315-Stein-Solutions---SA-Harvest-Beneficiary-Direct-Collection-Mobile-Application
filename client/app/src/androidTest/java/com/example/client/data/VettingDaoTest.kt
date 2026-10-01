package com.example.client.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.client.data.local.dao.FoodspaceBeneficiaryDao
import com.example.client.data.local.dao.VettingDecisionDao
import com.example.client.data.local.entity.DecisionOutcome
import com.example.client.testing.sampleDecision
import com.example.client.testing.sampleRecord
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The Vetting queries on a real (in-memory) Room database: the SQL, the converters and the transactions. */
@RunWith(AndroidJUnit4::class)
class VettingDaoTest {
    private lateinit var db: AppDatabase
    private lateinit var records: FoodspaceBeneficiaryDao
    private lateinit var decisions: VettingDecisionDao

    @Before
    fun createDb() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).build()
        records = db.foodspaceBeneficiaryDao()
        decisions = db.vettingDecisionDao()
    }

    @After
    fun closeDb() = db.close()

    @Test
    fun aRecord_roundTripsEveryField_includingListsWithCommasAndTheMissingOnes() = runBlocking {
        val original = sampleRecord("fs-1").copy(
            targetPopulation = listOf("Children, Youth", "Elderly"), // a comma inside a value
            ageGroups = listOf("0–5", "60+"),
            website = null, address = null, lastDateFed = null, pboCertificate = null,
            infrastructureChecks = emptyList()
        )

        records.replaceAll(listOf(original))

        assertEquals(original, records.getById("fs-1"))
    }

    @Test
    fun theCache_isReadAToZ_ignoringCase() = runBlocking {
        records.replaceAll(listOf(sampleRecord("1", legalName = "umlazi Hope"), sampleRecord("2", legalName = "Alpha"), sampleRecord("3", legalName = "beta")))

        assertEquals(listOf("Alpha", "beta", "umlazi Hope"), records.observeAllByName().first().map { it.legalName })
    }

    @Test
    fun replaceAll_swapsTheWholeList_dropsWhatIsGone_andUpdatesWhatChanged() = runBlocking {
        records.replaceAll(listOf(sampleRecord("a", legalName = "Old A"), sampleRecord("gone")))

        records.replaceAll(listOf(sampleRecord("a", legalName = "New A"), sampleRecord("new")))

        val now = records.observeAllByName().first()
        assertEquals(listOf("a", "new"), now.map { it.id }.sorted())
        assertEquals("New A", records.getById("a")?.legalName)
        assertNull(records.getById("gone"))
    }

    @Test
    fun replacingWithAnEmptyList_emptiesTheCache() = runBlocking {
        records.replaceAll(listOf(sampleRecord("a")))

        records.replaceAll(emptyList())

        assertEquals(0, records.observeAllByName().first().size)
    }

    @Test
    fun aSingleRecord_isObservedLive_andBecomesNullWhenItLeavesTheCache() = runBlocking {
        records.replaceAll(listOf(sampleRecord("a", legalName = "Alpha")))
        assertEquals("Alpha", records.observeById("a").first()?.legalName)

        records.replaceAll(emptyList())

        assertNull(records.observeById("a").first())
    }

    @Test
    fun decisions_surviveTheCacheBeingReplaced() = runBlocking {
        records.replaceAll(listOf(sampleRecord("a")))
        decisions.insert(sampleDecision("a", DecisionOutcome.APPROVE, at = 10))

        records.replaceAll(listOf(sampleRecord("b"))) // "a" has left Foodspace's list

        assertEquals(1, decisions.observeForRecord("a").first().size)
    }

    @Test
    fun decisions_areReadNewestFirst_perRecordAndOverall() = runBlocking {
        decisions.insert(sampleDecision("a", DecisionOutcome.FLAG, at = 10))
        decisions.insert(sampleDecision("a", DecisionOutcome.APPROVE, at = 30))
        decisions.insert(sampleDecision("b", DecisionOutcome.REJECT, at = 20))

        assertEquals(listOf(DecisionOutcome.APPROVE, DecisionOutcome.FLAG), decisions.observeForRecord("a").first().map { it.outcome })
        assertEquals(listOf(DecisionOutcome.APPROVE, DecisionOutcome.REJECT, DecisionOutcome.FLAG), decisions.observeAllNewestFirst().first().map { it.outcome })
        assertEquals(emptyList<DecisionOutcome>(), decisions.observeForRecord("none").first().map { it.outcome })
    }
}
