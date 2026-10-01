package com.example.client.ui.cbo

import com.example.client.auth.FakeTokenStorage
import com.example.client.auth.SessionManager
import com.example.client.auth.UserRole
import com.example.client.data.local.entity.CboCollectionEntity
import com.example.client.data.local.entity.ProductLineEntity
import com.example.client.data.local.entity.SyncStatus
import com.example.client.data.repository.CboCollectionRepository
import com.example.client.sync.CboSyncTrigger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The Form 1 flow end to end on the JVM: fill in, submit, save locally, queue a sync. */
@OptIn(ExperimentalCoroutinesApi::class)
class Form1ViewModelTest {

    private class FakeRepository : CboCollectionRepository {
        val saved = mutableListOf<Pair<CboCollectionEntity, List<ProductLineEntity>>>()
        var failWith: Exception? = null
        private val all = MutableStateFlow<List<CboCollectionEntity>>(emptyList())

        override suspend fun save(collection: CboCollectionEntity, productLines: List<ProductLineEntity>) {
            failWith?.let { throw it }
            saved += collection to productLines
            all.value = all.value + collection
        }

        override fun observeAll(): Flow<List<CboCollectionEntity>> = all
        override fun observeCount(status: SyncStatus): Flow<Int> = all.map { l -> l.count { it.syncStatus == status } }
    }

    private class FakeTrigger : CboSyncTrigger {
        var calls = 0
        override fun syncCboCollectionsNow() { calls++ }
    }

    private val dispatcher = StandardTestDispatcher()
    private lateinit var repository: FakeRepository
    private lateinit var trigger: FakeTrigger
    private lateinit var session: SessionManager
    private lateinit var viewModel: Form1ViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repository = FakeRepository()
        trigger = FakeTrigger()
        session = SessionManager(FakeTokenStorage())
        session.startSession("jwt", UserRole.CBO_COLLECTION, "cbo-7")
        viewModel = Form1ViewModel(repository, trigger, session)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun fillValidForm() {
        viewModel.onDonorNameChange("  Jane Donor ")
        viewModel.onOpenAddProduct()
        viewModel.onDraftCategoryChange("Fruit")
        viewModel.onDraftKgChange("42,5")
        viewModel.onDraftNotesChange("crates")
        viewModel.onConfirmAddProduct()
        viewModel.onToggleDonorSigned()
        viewModel.onToggleCboSigned()
        viewModel.onToggleShot(0)
        viewModel.onDeliveryNoteChange(" DN-1 ")
    }

    @Test
    fun submittingAValidForm_savesLocallyAsPending_andQueuesASync() = runTest(dispatcher) {
        fillValidForm()

        viewModel.onSubmit()
        advanceUntilIdle()

        val (collection, lines) = repository.saved.single()
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

        fillValidForm()
        viewModel.onSubmit()
        advanceUntilIdle()

        val ids = repository.saved.map { it.first.id }
        assertEquals(2, ids.size)
        assertEquals(2, ids.toSet().size)
    }

    @Test
    fun aProductWithABadQuantity_isNotAdded() = runTest(dispatcher) {
        viewModel.onOpenAddProduct()
        viewModel.onDraftKgChange("abc")

        viewModel.onConfirmAddProduct()

        assertEquals(Form1Error.INVALID_QUANTITY, viewModel.uiState.value.productDraft?.kgError)
        assertTrue(viewModel.uiState.value.form.productLines.isEmpty())
    }
}
