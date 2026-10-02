package com.example.client.ui.vetting

import com.example.client.data.local.entity.DecisionOutcome
import org.junit.Assert.assertEquals
import org.junit.Test

/** What the Vetting Sync tab counts: only decisions not yet on the server, and only ones a sync run would really send. */
class VettingSyncStateTest {

    private fun item(id: String, sync: DecisionSyncDisplay) = DecisionQueueItem(id, "Record $id", DecisionOutcome.APPROVE, 1_000L, sync)

    private val state = VettingSyncUiState(
        listOf(
            item("synced", DecisionSyncDisplay.SYNCED),
            item("pending", DecisionSyncDisplay.PENDING),
            item("retry", DecisionSyncDisplay.FAILED_WILL_RETRY),
            item("final", DecisionSyncDisplay.FAILED_FINAL),
            item("rejected", DecisionSyncDisplay.FAILED_REJECTED)
        )
    )

    @Test
    fun theQueue_isEverythingNotYetOnTheServer() {
        assertEquals(listOf("pending", "retry", "final", "rejected"), state.waiting.map { it.id })
    }

    @Test
    fun failedDecisions_areCounted() {
        assertEquals(3, state.failed)
    }

    @Test
    fun aSyncRun_onlySendsWhatCanStillSucceed() {
        // The server refused one and the app has stopped retrying another: sending them again cannot help.
        assertEquals(2, state.sendable)
    }

    @Test
    fun anEmptyQueue_hasNothingToSend() {
        val empty = VettingSyncUiState()
        assertEquals(0, empty.waiting.size)
        assertEquals(0, empty.sendable)
        assertEquals(0, empty.failed)
    }
}
