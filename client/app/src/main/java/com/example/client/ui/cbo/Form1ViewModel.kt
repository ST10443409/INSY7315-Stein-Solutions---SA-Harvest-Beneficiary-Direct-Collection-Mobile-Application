package com.example.client.ui.cbo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.client.auth.SessionManager
import com.example.client.data.attachments.AttachmentStorage
import com.example.client.data.attachments.CaptureTarget
import com.example.client.data.attachments.StoredFile
import com.example.client.data.local.entity.AttachmentKind
import com.example.client.data.local.entity.CboCollectionEntity
import com.example.client.data.local.entity.CollectionAttachmentEntity
import com.example.client.data.local.entity.ProductLineEntity
import com.example.client.data.repository.CboCollectionRepository
import com.example.client.sync.CboSyncTrigger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.inject.Inject

val PRODUCT_CATEGORIES = listOf(
    "Baker", "Beverages", "Dairy and eggs", "Dry Goods", "Frozen Foods",
    "Fruit", "Household and personal care", "Snacks and Confectionory", "Vegtables"
)

const val PHOTO_SHOT_COUNT = 4

/** One product line on the form; [kg] stays a string so partial input is preserved while typing. */
data class ProductLineInput(
    val id: String = UUID.randomUUID().toString(),
    val category: String,
    val kg: String,
    val notes: String
)

/** The "add product" dialog draft. Kept in the ViewModel so it survives rotation. */
data class ProductDraft(
    val category: String = PRODUCT_CATEGORIES.first(),
    val kg: String = "",
    val notes: String = "",
    val kgError: Form1Error? = null
)

/** Where a signature or photo goes on the form: what it is, and (for the numbered photos) which one. */
data class AttachmentSlot(val kind: AttachmentKind, val index: Int = 0) {
    companion object {
        val DonorSignature = AttachmentSlot(AttachmentKind.DONOR_SIGNATURE)
        val CboSignature = AttachmentSlot(AttachmentKind.CBO_SIGNATURE)
        val DeliveryNote = AttachmentSlot(AttachmentKind.DELIVERY_NOTE)
        fun photo(index: Int) = AttachmentSlot(AttachmentKind.PHOTO, index)
        fun signatureOf(kind: AttachmentKind) = AttachmentSlot(kind)
    }
}

/** A signature or photo already stored on the device but not yet saved with a collection. */
data class DraftAttachment(
    val id: String = UUID.randomUUID().toString(),
    val path: String,
    val mimeType: String,
    val sizeBytes: Long
)

/** Field-for-field mirror of CboCollectionEntity (plus its product lines and attachments). */
data class Form1FormState(
    val arrivalTime: String = "",
    val departureTime: String? = null,
    val productLines: List<ProductLineInput> = emptyList(),
    val donorName: String = "",
    val attachments: Map<AttachmentSlot, DraftAttachment> = emptyMap(),
    val deliveryNote: String = "",
    val collectNotes: String = ""
) {
    val totalKg: Double get() = productLines.sumOf { Form1Validator.parseKg(it.kg) ?: 0.0 }

    // What the server is told (see CboCollectionSyncDto): that something was signed or photographed.
    val donorSigned: Boolean get() = AttachmentSlot.DonorSignature in attachments
    val cboSigned: Boolean get() = AttachmentSlot.CboSignature in attachments
    val noteAttached: Boolean get() = AttachmentSlot.DeliveryNote in attachments
    val shots: List<Boolean> get() = List(PHOTO_SHOT_COUNT) { AttachmentSlot.photo(it) in attachments }
}

/** What the Done screen says about the collection that was just saved, taken from the saved record. */
data class CollectionReceipt(
    val reference: String,
    val donorName: String,
    val totalKg: Double,
    val arrivalTime: String,
    val departureTime: String?,
    val signatureCount: Int,
    val photoCount: Int,
    val deliveryNote: String
)

data class Form1UiState(
    val form: Form1FormState = Form1FormState(),
    val errors: Map<Form1Field, Form1Error> = emptyMap(),
    val productDraft: ProductDraft? = null,
    val isSaving: Boolean = false,
    val saveFailed: Boolean = false,
    /** A signature or photo could not be stored; cleared by the next successful one. */
    val attachmentFailed: Boolean = false,
    val receipt: CollectionReceipt? = null
) {
    val submitted: Boolean get() = receipt != null
}

@HiltViewModel
class Form1ViewModel @Inject constructor(
    private val repository: CboCollectionRepository,
    private val syncTrigger: CboSyncTrigger,
    private val sessionManager: SessionManager,
    private val storage: AttachmentStorage
) : ViewModel() {

    private val _uiState = MutableStateFlow(Form1UiState(form = Form1FormState(arrivalTime = nowTime())))
    val uiState: StateFlow<Form1UiState> = _uiState.asStateFlow()

    // The slot the camera is currently writing a photo for, and the file it was given.
    private var pendingCapture: Pair<AttachmentSlot, String>? = null

    // An edit clears that field's error and any previous save failure.
    private fun editForm(clears: Form1Field? = null, block: (Form1FormState) -> Form1FormState) =
        _uiState.update { s ->
            s.copy(
                form = block(s.form),
                errors = if (clears == null) s.errors else s.errors - clears,
                saveFailed = false
            )
        }

    fun onDonorNameChange(value: String) = editForm(Form1Field.DONOR_NAME) { it.copy(donorName = value) }
    fun onDeliveryNoteChange(value: String) = editForm { it.copy(deliveryNote = value) }
    fun onCollectNotesChange(value: String) = editForm { it.copy(collectNotes = value) }

    fun onStampDeparture() = editForm(Form1Field.DEPARTURE) { it.copy(departureTime = nowTime()) }

    // --- Signatures and photos ---

    /** The signature pad was accepted: stores the drawn signature (PNG) for [kind]'s slot. */
    fun onSignatureCaptured(kind: AttachmentKind, png: ByteArray) {
        require(kind == AttachmentKind.DONOR_SIGNATURE || kind == AttachmentKind.CBO_SIGNATURE) { "$kind is not a signature" }
        viewModelScope.launch { attach(AttachmentSlot.signatureOf(kind), runCatching { storage.saveSignature(png) }.getOrNull()) }
    }

    /** The user picked a picture from the gallery for [slot]. */
    fun onPhotoPicked(slot: AttachmentSlot, sourceUri: String) {
        viewModelScope.launch { attach(slot, runCatching { storage.savePhoto(sourceUri) }.getOrNull()) }
    }

    /** The camera is about to be opened for [slot]: reserves the file it should write to. */
    fun newCaptureTarget(slot: AttachmentSlot): CaptureTarget {
        pendingCapture?.let { (_, path) -> storage.delete(path) } // an earlier attempt that never reported back
        return storage.newCaptureTarget().also { pendingCapture = slot to it.path }
    }

    /** The camera finished: [success] is false if the user backed out without taking a photo. */
    fun onCaptureResult(success: Boolean) {
        val (slot, path) = pendingCapture ?: return
        pendingCapture = null
        if (!success) {
            storage.delete(path)
            return
        }
        viewModelScope.launch { attach(slot, runCatching { storage.finishCapture(path) }.getOrNull()) }
    }

    fun onRemoveAttachment(slot: AttachmentSlot) {
        val removed = _uiState.value.form.attachments[slot] ?: return
        editForm(slot.errorField()) { it.copy(attachments = it.attachments - slot) }
        storage.delete(removed.path)
    }

    private fun attach(slot: AttachmentSlot, stored: StoredFile?) {
        if (stored == null) {
            _uiState.update { it.copy(attachmentFailed = true) }
            return
        }
        val replaced = _uiState.value.form.attachments[slot]
        editForm(slot.errorField()) {
            it.copy(attachments = it.attachments + (slot to DraftAttachment(path = stored.path, mimeType = stored.mimeType, sizeBytes = stored.sizeBytes)))
        }
        _uiState.update { it.copy(attachmentFailed = false) }
        replaced?.let { storage.delete(it.path) }
    }

    private fun AttachmentSlot.errorField(): Form1Field? = when (kind) {
        AttachmentKind.DONOR_SIGNATURE, AttachmentKind.CBO_SIGNATURE -> Form1Field.SIGNATURES
        AttachmentKind.PHOTO -> Form1Field.PHOTOS
        AttachmentKind.DELIVERY_NOTE -> null
    }

    // --- Product line dialog ---
    fun onOpenAddProduct() = _uiState.update { it.copy(productDraft = ProductDraft()) }
    fun onDismissAddProduct() = _uiState.update { it.copy(productDraft = null) }

    fun onDraftCategoryChange(value: String) = updateDraft { it.copy(category = value) }
    fun onDraftKgChange(value: String) = updateDraft { it.copy(kg = value, kgError = null) }
    fun onDraftNotesChange(value: String) = updateDraft { it.copy(notes = value) }

    private fun updateDraft(block: (ProductDraft) -> ProductDraft) =
        _uiState.update { s -> s.productDraft?.let { s.copy(productDraft = block(it)) } ?: s }

    fun onConfirmAddProduct() {
        val draft = _uiState.value.productDraft ?: return
        val error = Form1Validator.validateKg(draft.kg)
        if (error != null) {
            updateDraft { it.copy(kgError = error) }
            return
        }
        editForm(Form1Field.PRODUCTS) { f ->
            f.copy(
                productLines = f.productLines + ProductLineInput(
                    category = draft.category,
                    kg = draft.kg.trim().replace(',', '.'),
                    notes = draft.notes.trim()
                )
            )
        }
        _uiState.update { it.copy(productDraft = null) }
    }

    fun onRemoveProduct(id: String) =
        editForm(Form1Field.PRODUCTS) { f -> f.copy(productLines = f.productLines.filterNot { it.id == id }) }

    // --- Submit ---
    fun onSubmit() {
        val state = _uiState.value
        if (state.isSaving || state.submitted) return
        val errors = Form1Validator.validate(state.form)
        if (errors.isNotEmpty()) {
            _uiState.update { it.copy(errors = errors, saveFailed = false) }
            return
        }
        _uiState.update { it.copy(errors = emptyMap(), isSaving = true, saveFailed = false) }

        val form = state.form
        val now = System.currentTimeMillis()
        val collection = CboCollectionEntity(
            cboId = sessionManager.cboId() ?: UNASSIGNED_CBO_ID,
            arrivalTime = form.arrivalTime,
            departureTime = form.departureTime,
            donorName = form.donorName.trim(),
            donorSigned = form.donorSigned,
            cboSigned = form.cboSigned,
            deliveryNote = form.deliveryNote.trim(),
            noteAttached = form.noteAttached,
            collectNotes = form.collectNotes.trim(),
            shots = form.shots,
            latitude = null,
            longitude = null,
            createdAt = now,
            updatedAt = now
        )
        val lines = form.productLines.map {
            ProductLineEntity(
                collectionId = collection.id,
                category = it.category,
                kg = it.kg,
                notes = it.notes.ifBlank { null },
                createdAt = now,
                updatedAt = now
            )
        }
        val attachments = form.attachments.map { (slot, draft) ->
            CollectionAttachmentEntity(
                id = draft.id,
                collectionId = collection.id,
                kind = slot.kind,
                slot = slot.index,
                filePath = draft.path,
                mimeType = draft.mimeType,
                sizeBytes = draft.sizeBytes,
                createdAt = now,
                updatedAt = now
            )
        }
        viewModelScope.launch {
            try {
                repository.save(collection, lines, attachments)
                // Queued, not awaited: it waits for a network if there is none, and the save never depends on it.
                syncTrigger.syncCboCollectionsNow()
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        // The files now belong to the saved collection, so the draft must not delete them.
                        form = it.form.copy(attachments = emptyMap()),
                        receipt = CollectionReceipt(
                            reference = "COL-" + collection.id.take(8).uppercase(Locale.US),
                            donorName = collection.donorName,
                            totalKg = form.totalKg,
                            arrivalTime = collection.arrivalTime,
                            departureTime = collection.departureTime,
                            signatureCount = listOf(form.donorSigned, form.cboSigned).count { signed -> signed },
                            photoCount = form.shots.count { taken -> taken },
                            deliveryNote = collection.deliveryNote
                        )
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isSaving = false, saveFailed = true) }
            }
        }
    }

    /** Clears the form for the next collection after the receipt has been seen. */
    fun onStartNew() {
        discardDraftFiles()
        _uiState.value = Form1UiState(form = Form1FormState(arrivalTime = nowTime()))
    }

    override fun onCleared() = discardDraftFiles()

    // Files that were captured but never saved with a collection are of no use to anyone.
    private fun discardDraftFiles() {
        _uiState.value.form.attachments.values.forEach { storage.delete(it.path) }
        pendingCapture?.let { (_, path) -> storage.delete(path) }
        pendingCapture = null
    }

    private fun nowTime(): String = SimpleDateFormat("HH:mm", Locale.US).format(Date())

    companion object {
        /**
         * Stored when the signed-in user has no CBO (an Admin, or a session from before CBOs were issued). The server
         * stamps a collector's own CBO on every record it receives, so this never survives sync for a collector.
         */
        const val UNASSIGNED_CBO_ID = "unassigned"
    }
}
