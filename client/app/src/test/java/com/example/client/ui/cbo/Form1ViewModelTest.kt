package com.example.client.ui.cbo

import com.example.client.auth.FakeTokenStorage
import com.example.client.auth.SessionManager
import com.example.client.auth.UserRole
import com.example.client.data.local.entity.AttachmentKind
import com.example.client.data.local.entity.SyncStatus
import com.example.client.testing.CountingCboSyncTrigger
import com.example.client.testing.FakeAttachmentStorage
import com.example.client.testing.InMemoryCboCollectionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The Form 1 flow end to end on the JVM: fill in, sign, photograph, submit, save locally, queue a sync. */
@OptIn(ExperimentalCoroutinesApi::class)
class Form1ViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var repository: InMemoryCboCollectionRepository
    private lateinit var trigger: CountingCboSyncTrigger
    private lateinit var storage: FakeAttachmentStorage
    private lateinit var session: SessionManager
    private lateinit var viewModel: Form1ViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repository = InMemoryCboCollectionRepository()
        trigger = CountingCboSyncTrigger()
        storage = FakeAttachmentStorage()
        session = SessionManager(FakeTokenStorage())
        session.startSession("jwt", UserRole.CBO_COLLECTION, "cbo-7")
        viewModel = Form1ViewModel(repository, trigger, session, storage)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val png = byteArrayOf(1, 2, 3)

    private fun TestScope.sign(kind: AttachmentKind) {
        viewModel.onSignatureCaptured(kind, png)
        advanceUntilIdle()
    }

    private fun TestScope.fillValidForm() {
        viewModel.onDonorNameChange("  Jane Donor ")
        viewModel.onOpenAddProduct()
        viewModel.onDraftCategoryChange("Fruit")
        viewModel.onDraftKgChange("42,5")
        viewModel.onDraftNotesChange("crates")
        viewModel.onConfirmAddProduct()
        sign(AttachmentKind.DONOR_SIGNATURE)
        sign(AttachmentKind.CBO_SIGNATURE)
        viewModel.onPhotoPicked(AttachmentSlot.photo(0), "content://gallery/1")
        advanceUntilIdle()
        viewModel.onDeliveryNoteChange(" DN-1 ")
    }

    @Test
    fun submittingAValidForm_savesLocallyAsPending_andQueuesASync() = runTest(dispatcher) {
        fillValidForm()

        viewModel.onSubmit()
        advanceUntilIdle()

        val (collection, lines, _) = repository.saved.single()
        assertEquals("Jane Donor", collection.donorName)
        assertEquals("DN-1", collection.deliveryNote)
        assertEquals(SyncStatus.PENDING, collection.syncStatus)
        assertEquals(0, collection.retryCount)
        assertNull(collection.syncErrorCode)
        val line = lines.single()
        assertEquals(collection.id, line.collectionId)
        assertEquals("Fruit", line.category)
        assertEquals("42.5", line.kg) // the comma is normalised
        assertEquals("crates", line.notes)
        assertEquals(1, trigger.calls)
        assertTrue(viewModel.uiState.value.submitted)
        assertFalse(viewModel.uiState.value.isSaving)
    }

    @Test
    fun theSavedCollection_isToldWhichSignaturesAndPhotosWereCaptured() = runTest(dispatcher) {
        fillValidForm()

        viewModel.onSubmit()
        advanceUntilIdle()

        val collection = repository.saved.single().first
        assertTrue(collection.donorSigned)
        assertTrue(collection.cboSigned)
        assertEquals(listOf(true, false, false, false), collection.shots)
        assertFalse(collection.noteAttached)
    }

    @Test
    fun theSignaturesAndPhotos_areSavedWithTheCollection_asFileBackedAttachments() = runTest(dispatcher) {
        fillValidForm()
        viewModel.onPhotoPicked(AttachmentSlot.DeliveryNote, "content://gallery/note")
        advanceUntilIdle()

        viewModel.onSubmit()
        advanceUntilIdle()

        val (collection, _, attachments) = repository.saved.single()
        assertEquals(
            setOf(AttachmentKind.DONOR_SIGNATURE, AttachmentKind.CBO_SIGNATURE, AttachmentKind.PHOTO, AttachmentKind.DELIVERY_NOTE),
            attachments.map { it.kind }.toSet()
        )
        assertTrue(attachments.all { it.collectionId == collection.id })
        assertTrue(attachments.all { it.syncStatus == SyncStatus.PENDING })
        assertEquals("image/png", attachments.first { it.kind == AttachmentKind.DONOR_SIGNATURE }.mimeType)
        assertEquals("image/jpeg", attachments.first { it.kind == AttachmentKind.PHOTO }.mimeType)
        assertTrue("saved files must not be cleaned up", attachments.all { it.filePath in storage.files })
    }

    @Test
    fun theReceipt_describesTheSavedRecord() = runTest(dispatcher) {
        fillValidForm()
        viewModel.onSubmit()
        advanceUntilIdle()

        val receipt = viewModel.uiState.value.receipt!!
        val collection = repository.saved.single().first
        assertEquals("Jane Donor", receipt.donorName)
        assertEquals(42.5, receipt.totalKg, 0.0)
        assertEquals(2, receipt.signatureCount)
        assertEquals(1, receipt.photoCount)
        assertEquals("DN-1", receipt.deliveryNote)
        assertEquals("COL-" + collection.id.take(8).uppercase(), receipt.reference)
    }

    @Test
    fun theRecord_isStoredUnderTheSignedInCollectorsCbo() = runTest(dispatcher) {
        fillValidForm()

        viewModel.onSubmit()
        advanceUntilIdle()

        assertEquals("cbo-7", repository.saved.single().first.cboId)
    }

    @Test
    fun withoutACbo_theRecordIsStoredAsUnassigned() = runTest(dispatcher) {
        session.startSession("jwt-2", UserRole.ADMIN) // an Admin has no CBO
        fillValidForm()

        viewModel.onSubmit()
        advanceUntilIdle()

        assertEquals(Form1ViewModel.UNASSIGNED_CBO_ID, repository.saved.single().first.cboId)
    }

    @Test
    fun anInvalidForm_isNotSaved_andShowsItsErrors() = runTest(dispatcher) {
        viewModel.onSubmit() // nothing filled in
        advanceUntilIdle()

        assertTrue(repository.saved.isEmpty())
        assertEquals(0, trigger.calls)
        val errors = viewModel.uiState.value.errors
        assertEquals(Form1Error.REQUIRED, errors[Form1Field.DONOR_NAME])
        assertEquals(Form1Error.NO_PRODUCT_LINES, errors[Form1Field.PRODUCTS])
        assertEquals(Form1Error.SIGNATURE_REQUIRED, errors[Form1Field.SIGNATURES])
        assertEquals(Form1Error.PHOTO_REQUIRED, errors[Form1Field.PHOTOS])
        assertFalse(viewModel.uiState.value.submitted)
    }

    @Test
    fun aFailedLocalSave_keepsTheForm_reportsIt_andDoesNotQueueASync() = runTest(dispatcher) {
        fillValidForm()
        repository.failWith = IllegalStateException("disk full")

        viewModel.onSubmit()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.saveFailed)
        assertFalse(state.submitted)
        assertFalse(state.isSaving)
        assertEquals("Jane Donor", state.form.donorName.trim())
        assertEquals(1, state.form.productLines.size)
        assertTrue("the captured pictures must still be there to retry", state.form.donorSigned && state.form.shots[0])
        assertEquals(0, trigger.calls)
    }

    @Test
    fun afterAFailedSave_theCollectorCanTryAgain() = runTest(dispatcher) {
        fillValidForm()
        repository.failWith = IllegalStateException("disk full")
        viewModel.onSubmit()
        advanceUntilIdle()

        repository.failWith = null
        viewModel.onSubmit()
        advanceUntilIdle()

        assertEquals(1, repository.saved.size)
        assertTrue(viewModel.uiState.value.submitted)
        assertFalse(viewModel.uiState.value.saveFailed)
    }

    @Test
    fun submittingTwice_savesOnce() = runTest(dispatcher) {
        fillValidForm()

        viewModel.onSubmit()
        viewModel.onSubmit() // a second tap before the first finished
        advanceUntilIdle()
        viewModel.onSubmit() // and one after the success state appeared
        advanceUntilIdle()

        assertEquals(1, repository.saved.size)
        assertEquals(1, trigger.calls)
    }

    @Test
    fun startingANewRecord_clearsTheForm_butTheNextRecordGetsItsOwnId() = runTest(dispatcher) {
        fillValidForm()
        viewModel.onSubmit()
        advanceUntilIdle()

        viewModel.onStartNew()
        assertFalse(viewModel.uiState.value.submitted)
        assertTrue(viewModel.uiState.value.form.productLines.isEmpty())
        assertFalse(viewModel.uiState.value.form.donorSigned)

        fillValidForm()
        viewModel.onSubmit()
        advanceUntilIdle()

        val ids = repository.saved.map { it.first.id }
        assertEquals(2, ids.size)
        assertEquals(2, ids.toSet().size)
    }

    @Test
    fun startingANewRecord_doesNotDeleteTheFilesOfTheRecordJustSaved() = runTest(dispatcher) {
        fillValidForm()
        viewModel.onSubmit()
        advanceUntilIdle()
        val saved = repository.saved.single().third

        viewModel.onStartNew()

        assertTrue(saved.all { it.filePath in storage.files })
        assertTrue(storage.deleted.isEmpty())
    }

    @Test
    fun aProductWithABadQuantity_isNotAdded() = runTest(dispatcher) {
        viewModel.onOpenAddProduct()
        viewModel.onDraftKgChange("abc")

        viewModel.onConfirmAddProduct()

        assertEquals(Form1Error.INVALID_QUANTITY, viewModel.uiState.value.productDraft?.kgError)
        assertTrue(viewModel.uiState.value.form.productLines.isEmpty())
    }

    // --- Signatures and photos ---

    @Test
    fun aCapturedSignature_marksThatPartySigned_andClearsTheSignatureError() = runTest(dispatcher) {
        viewModel.onSubmit()
        advanceUntilIdle()
        assertNotNull(viewModel.uiState.value.errors[Form1Field.SIGNATURES])

        sign(AttachmentKind.DONOR_SIGNATURE)

        val state = viewModel.uiState.value
        assertTrue(state.form.donorSigned)
        assertFalse(state.form.cboSigned)
        assertNull(state.errors[Form1Field.SIGNATURES])
    }

    @Test
    fun signingAgain_replacesTheSignature_andRemovesTheOldFile() = runTest(dispatcher) {
        sign(AttachmentKind.DONOR_SIGNATURE)
        val first = viewModel.uiState.value.form.attachments.getValue(AttachmentSlot.DonorSignature).path

        sign(AttachmentKind.DONOR_SIGNATURE)

        val second = viewModel.uiState.value.form.attachments.getValue(AttachmentSlot.DonorSignature).path
        assertTrue(first != second)
        assertFalse(first in storage.files)
        assertTrue(second in storage.files)
    }

    @Test
    fun aPhotoChosenFromTheGallery_fillsThatSlot_andClearsThePhotoError() = runTest(dispatcher) {
        viewModel.onSubmit()
        advanceUntilIdle()
        assertNotNull(viewModel.uiState.value.errors[Form1Field.PHOTOS])

        viewModel.onPhotoPicked(AttachmentSlot.photo(2), "content://gallery/9")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf(false, false, true, false), state.form.shots)
        assertNull(state.errors[Form1Field.PHOTOS])
    }

    @Test
    fun aPhotoFromTheCamera_isKept_whenTheCameraReportsSuccess() = runTest(dispatcher) {
        val target = viewModel.newCaptureTarget(AttachmentSlot.photo(1))

        viewModel.onCaptureResult(success = true)
        advanceUntilIdle()

        val photo = viewModel.uiState.value.form.attachments.getValue(AttachmentSlot.photo(1))
        assertEquals(target.path, photo.path)
        assertTrue(photo.path in storage.files)
    }

    @Test
    fun aCancelledCamera_attachesNothing_andLeavesNoFileBehind() = runTest(dispatcher) {
        val target = viewModel.newCaptureTarget(AttachmentSlot.photo(1))

        viewModel.onCaptureResult(success = false)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.form.attachments.isEmpty())
        assertFalse(target.path in storage.files)
    }

    @Test
    fun aPictureThatCannotBeStored_isReported_andAttachesNothing() = runTest(dispatcher) {
        storage.failSaving = true

        viewModel.onPhotoPicked(AttachmentSlot.photo(0), "content://gallery/broken")
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.attachmentFailed)
        assertTrue(viewModel.uiState.value.form.attachments.isEmpty())

        storage.failSaving = false
        viewModel.onPhotoPicked(AttachmentSlot.photo(0), "content://gallery/fine")
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.attachmentFailed)
    }

    @Test
    fun removingAPhoto_emptiesItsSlot_andDeletesItsFile() = runTest(dispatcher) {
        viewModel.onPhotoPicked(AttachmentSlot.photo(0), "content://gallery/1")
        advanceUntilIdle()
        val path = viewModel.uiState.value.form.attachments.getValue(AttachmentSlot.photo(0)).path

        viewModel.onRemoveAttachment(AttachmentSlot.photo(0))

        assertFalse(viewModel.uiState.value.form.shots[0])
        assertFalse(path in storage.files)
    }

    @Test
    fun theDeliveryNotePhoto_isTrackedSeparatelyFromTheDonationPhotos() = runTest(dispatcher) {
        viewModel.onPhotoPicked(AttachmentSlot.DeliveryNote, "content://gallery/note")
        advanceUntilIdle()

        val form = viewModel.uiState.value.form
        assertTrue(form.noteAttached)
        assertTrue(form.shots.none { it })
    }

    @Test
    fun aFormThatIsAbandoned_cleansUpItsUnsavedFiles() = runTest(dispatcher) {
        sign(AttachmentKind.DONOR_SIGNATURE)
        viewModel.onPhotoPicked(AttachmentSlot.photo(0), "content://gallery/1")
        advanceUntilIdle()
        assertEquals(2, storage.files.size)

        // onCleared is protected; the ViewModel's own clean-up is reachable through starting over.
        viewModel.onStartNew()

        assertTrue(storage.files.isEmpty())
    }
}
