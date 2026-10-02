package com.example.client.ui.admin

import com.example.client.data.local.entity.CboCollectionEntity
import com.example.client.data.local.entity.DecisionOutcome
import com.example.client.testing.FakeVettingRepository
import com.example.client.testing.InMemoryCboCollectionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** The Reports figures: collections stored, and vettings approved, flagged and denied, from real stored records. */
@OptIn(ExperimentalCoroutinesApi::class)
class ReportsViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var collections: InMemoryCboCollectionRepository
    private lateinit var vetting: FakeVettingRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        collections = InMemoryCboCollectionRepository()
        vetting = FakeVettingRepository()
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private suspend fun saveCollection(donor: String) = collections.save(
        CboCollectionEntity(
            cboId = "cbo-1", arrivalTime = "09:00", departureTime = null, donorName = donor, donorSigned = true, cboSigned = true,
            deliveryNote = "", noteAttached = false, collectNotes = "", shots = listOf(true, false, false, false),
            latitude = null, longitude = null
        ),
        emptyList()
    )

    private fun TestScope.current(): ReportsUiState {
        val viewModel = ReportsViewModel(collections, vetting)
        backgroundScope.launch { viewModel.uiState.collect { } } // the state only runs while someone is watching it
        advanceUntilIdle()
        return viewModel.uiState.value
    }

    @Test
    fun withNothingStored_everyFigureIsZero() = runTest(dispatcher) {
        assertEquals(ReportsUiState(), current())
    }

    @Test
    fun theFiguresCountWhatIsStored() = runTest(dispatcher) {
        saveCollection("A")
        saveCollection("B")
        saveCollection("C")
        vetting.saveDecision("r1", DecisionOutcome.APPROVE, null, "officer")
        vetting.saveDecision("r2", DecisionOutcome.APPROVE, null, "officer")
        vetting.saveDecision("r3", DecisionOutcome.FLAG, null, "officer")
        vetting.saveDecision("r4", DecisionOutcome.REJECT, null, "officer")

        val state = current()

        assertEquals(3, state.collections)
        assertEquals(2, state.approved)
        assertEquals(1, state.flagged)
        assertEquals(1, state.denied)
        assertEquals(4, state.vettings)
    }

    @Test
    fun aRecordCountsOnce_byItsCurrentDecision() = runTest(dispatcher) {
        vetting.saveDecision("r1", DecisionOutcome.FLAG, null, "officer")
        vetting.saveDecision("r1", DecisionOutcome.APPROVE, null, "officer") // the officer changed their mind

        val state = current()

        assertEquals(1, state.approved)
        assertEquals(0, state.flagged)
        assertEquals(1, state.vettings)
    }
}
