package com.example.client.ui.cbo

import com.example.client.data.local.entity.AttachmentKind
import com.example.client.data.local.entity.CboCollectionEntity
import com.example.client.data.local.entity.CollectionAttachmentEntity
import com.example.client.data.local.entity.SyncStatus
import com.example.client.network.CboSyncErrorCodes
import com.example.client.sync.CboSyncProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubmissionsTest {

    private fun record(id: String, status: SyncStatus, retryCount: Int = 0, errorCode: String? = null) = CboCollectionEntity(
        id = id, cboId = "cbo", arrivalTime = "09:00", departureTime = null, donorName = "Donor $id",
        donorSigned = true, cboSigned = true, deliveryNote = "", noteAttached = false, collectNotes = "",
        shots = listOf(true), latitude = null, longitude = null, syncStatus = status, retryCount = retryCount, syncErrorCode = errorCode
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
    fun aDuplicate_isShownAsADuplicate_evenThoughItIsNeverRetried() {
        val duplicate = record("e", SyncStatus.FAILED, CboSyncProcessor.MAX_RETRIES, CboSyncErrorCodes.DUPLICATE_DETECTED)

        assertEquals(SubmissionDisplay.FAILED_DUPLICATE, duplicate.toSubmissionItem().display)
    }

    @Test
    fun aRejectedRecord_isToldApartFromOneThatSimplyRanOutOfRetries() {
        val rejected = record("f", SyncStatus.FAILED, CboSyncProcessor.MAX_RETRIES, CboSyncErrorCodes.VALIDATION_FAILED)
        val ranOut = record("g", SyncStatus.FAILED, CboSyncProcessor.MAX_RETRIES, "SERVER_ERROR")

        assertEquals(SubmissionDisplay.FAILED_REJECTED, rejected.toSubmissionItem().display)
        assertEquals(SubmissionDisplay.FAILED_FINAL, ranOut.toSubmissionItem().display)
    }

    @Test
    fun aRecordStillHavingRetries_isNeverShownAsRejected() {
        val retrying = record("h", SyncStatus.FAILED, 1, "SERVER_ERROR")

        assertEquals(SubmissionDisplay.FAILED_WILL_RETRY, retrying.toSubmissionItem().display)
    }

    @Test
    fun everyFailureKindCountsAsNeedingAttention() {
        val failures = listOf(
            SubmissionDisplay.FAILED_WILL_RETRY, SubmissionDisplay.FAILED_FINAL,
            SubmissionDisplay.FAILED_DUPLICATE, SubmissionDisplay.FAILED_REJECTED
        )

        assertTrue(failures.all { it.isFailed })
        assertTrue(listOf(SubmissionDisplay.PENDING, SubmissionDisplay.SYNCED).none { it.isFailed })
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

    
    fun theQueue_isEverythingNotYetOnTheServer_andASyncOnlySendsWhatCanStillSucceed() {
        val state = SubmissionsUiState(
            listOf(
                record("1", SyncStatus.PENDING), record("2", SyncStatus.SYNCED),
                record("3", SyncStatus.FAILED, 1), record("4", SyncStatus.FAILED, CboSyncProcessor.MAX_RETRIES)
            ).map { it.toSubmissionItem() }
        )

        assertEquals(listOf("1", "3", "4"), state.waiting.map { it.id })
        assertEquals(2, state.sendable) // the pending one and the one with retries left
    }

    
    fun aSubmission_knowsHowManySignaturesAndPhotosWereCaptured() {
        fun attachment(kind: AttachmentKind, slot: Int = 0) =
            CollectionAttachmentEntity(collectionId = "1", kind = kind, slot = slot, filePath = "/f", mimeType = "image/png", sizeBytes = 1)

        val item = record("1", SyncStatus.PENDING).toSubmissionItem(
            listOf(
                attachment(AttachmentKind.DONOR_SIGNATURE), attachment(AttachmentKind.CBO_SIGNATURE),
                attachment(AttachmentKind.PHOTO, 0), attachment(AttachmentKind.PHOTO, 1), attachment(AttachmentKind.DELIVERY_NOTE)
            )
        )

        assertEquals(2, item.signatureCount)
        assertEquals(2, item.photoCount) // the delivery note photo is not one of the donation photos
    }
}
