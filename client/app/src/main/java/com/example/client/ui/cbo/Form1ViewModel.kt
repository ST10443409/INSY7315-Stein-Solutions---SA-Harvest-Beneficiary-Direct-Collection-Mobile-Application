package com.example.client.ui.cbo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.client.data.local.entity.CboCollectionEntity
import com.example.client.data.local.entity.ProductLineEntity
import com.example.client.data.repository.CboCollectionRepository
import com.example.client.sync.SyncScheduler
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

/** Field-for-field mirror of CboCollectionEntity (plus its product lines). */
data class Form1FormState(
    val arrivalTime: String = "",
    val departureTime: String? = null,
    val productLines: List<ProductLineInput> = emptyList(),
    val donorName: String = "",
    val donorSigned: Boolean = false,
    val cboSigned: Boolean = false,
    val shots: List<Boolean> = List(PHOTO_SHOT_COUNT) { false },
    val deliveryNote: String = "",
    val noteAttached: Boolean = false,
    val collectNotes: String = ""
) {
    val totalKg: Double get() = productLines.sumOf { Form1Validator.parseKg(it.kg) ?: 0.0 }
}

data class Form1UiState(
    val form: Form1FormState = Form1FormState(),
    val errors: Map<Form1Field, Form1Error> = emptyMap(),
    val productDraft: ProductDraft? = null,
    val isSaving: Boolean = false,
    val saveFailed: Boolean = false,
    val submitted: Boolean = false
)

@HiltViewModel
class Form1ViewModel @Inject constructor(
    private val repository: CboCollectionRepository,
    private val syncScheduler: SyncScheduler
) : ViewModel() {

    private val _uiState = MutableStateFlow(Form1UiState(form = Form1FormState(arrivalTime = nowTime())))
    val uiState: StateFlow<Form1UiState> = _uiState.asStateFlow()

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
    fun onToggleNoteAttached() = editForm { it.copy(noteAttached = !it.noteAttached) }

    fun onStampDeparture() = editForm(Form1Field.DEPARTURE) { it.copy(departureTime = nowTime()) }

    // Signature pad and camera capture are separate work; these record that it happened.
    fun onToggleDonorSigned() = editForm(Form1Field.SIGNATURES) { it.copy(donorSigned = !it.donorSigned) }
    fun onToggleCboSigned() = editForm(Form1Field.SIGNATURES) { it.copy(cboSigned = !it.cboSigned) }
    fun onToggleShot(index: Int) = editForm(Form1Field.PHOTOS) { f ->
        f.copy(shots = f.shots.mapIndexed { i, taken -> if (i == index) !taken else taken })
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
            cboId = DEFAULT_CBO_ID,
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
        viewModelScope.launch {
            try {
                repository.save(collection, lines)
                // Queued, not awaited: it waits for a network if there is none, and the save never depends on it.
                syncScheduler.syncCboCollectionsNow()
                _uiState.update { it.copy(isSaving = false, submitted = true) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isSaving = false, saveFailed = true) }
            }
        }
    }

    /** Clears the form for the next collection after the success state. */
    fun onStartNew() {
        _uiState.value = Form1UiState(form = Form1FormState(arrivalTime = nowTime()))
    }

    private fun nowTime(): String = SimpleDateFormat("HH:mm", Locale.US).format(Date())

    companion object {
        // TODO(#34): the session carries no CBO id yet; replace with the signed-in collector's CBO.
        const val DEFAULT_CBO_ID = "unassigned"
    }
}
