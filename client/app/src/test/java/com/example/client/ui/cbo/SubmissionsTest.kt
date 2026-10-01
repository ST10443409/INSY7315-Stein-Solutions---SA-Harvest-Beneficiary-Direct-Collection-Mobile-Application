package com.example.client.ui.cbo

import com.example.client.data.local.entity.CboCollectionEntity
import com.example.client.data.local.entity.SyncStatus
import com.example.client.sync.CboSyncProcessor
import org.junit.Assert.assertEquals
import org.junit.Test

class SubmissionsTest {

    private fun record(id: String, status: SyncStatus, retryCount: Int = 0) = CboCollectionEntity(
        id = id, cboId = "cbo", arrivalTime = "09:00", departureTime = null, donorName = "Donor $id",
        donorSigned = true, cboSigned = true, deliveryNote = "", noteAttached = false, collectNotes = "",
        shots = listOf(true), latitude = null, longitude = null, syncStatus = status, retryCount = retryCount
    )

    @Test
    fun eachStatusMapsToItsOwnDisplay() {
        assertEquals(SubmissionDisplay.PENDING, record("a", SyncStatus.PENDING).toSubmissionItem().display)
        assertEquals(SubmissionDisplay.SYNCED, record("b", SyncStatus.SYNCED).toSubmissionItem().display)
        assertEquals(SubmissionDisplay.FAILED_WILL_RETRY, record("c", SyncStatus.FAILED, 1).toSubmissionItem().display)
    }

    @Test
    fun failedRecordsOutOfRetriesAreMarkedFinal_soTheCollectorKnowsToAskForHelp() {
        val exhausted = record("d", SyncStatus.FAILED, CboSyncProcessor.MAX_RETRIES).toSubmissionItem()

        assertEquals(SubmissionDisplay.FAILED_FINAL, exhausted.display)
    }

    @Test
    fun countsAreDerivedFromTheList_andFailedIsCountedSeparatelyFromPending() {
        val state = SubmissionsUiState(
            listOf(
                record("1", SyncStatus.PENDING), record("2", SyncStatus.PENDING),
                record("3", SyncStatus.SYNCED),
                record("4", SyncStatus.FAILED, 1), record("5", SyncStatus.FAILED, CboSyncProcessor.MAX_RETRIES)
            ).map { it.toSubmissionItem() }
        )

        assertEquals(2, state.pending)
        assertEquals(1, state.synced)
        assertEquals(2, state.failed)
    }

    @Test
    fun aRecordIsNeverDroppedFromTheList_whateverItsStatus() {
        val all = SyncStatus.values().map { record(it.name, it).toSubmissionItem() }

        assertEquals(SyncStatus.values().size, SubmissionsUiState(all).items.size)
    }
}
