package com.example.client.ui.cbo

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.client.R
import com.example.client.ui.placeholder.ScreenTags

object Form1Tags {
    const val DONOR_NAME = "form1_donor_name"
    const val DELIVERY_NOTE = "form1_delivery_note"
    const val NOTES = "form1_notes"
    const val ADD_PRODUCT = "form1_add_product"
    const val DRAFT_KG = "form1_draft_kg"
    const val DRAFT_CONFIRM = "form1_draft_confirm"
    const val SIGN_DONOR = "form1_sign_donor"
    const val SIGN_CBO = "form1_sign_cbo"
    const val PHOTO_PREFIX = "form1_photo_"
    const val STAMP_DEPARTURE = "form1_stamp_departure"
    const val SUBMIT = "form1_submit"
    const val SUCCESS = "form1_success"
    const val NEW_COLLECTION = "form1_new_collection"
    const val SAVE_FAILED = "form1_save_failed"
    fun error(field: Form1Field) = "form1_error_${field.name.lowercase()}"
}

private val Yellow = Color(0xFFFFD400)
private val Ink = Color(0xFF1A1A1A)

@StringRes
private fun Form1Error.message(): Int = when (this) {
    Form1Error.REQUIRED -> R.string.form1_err_required
    Form1Error.INVALID_TIME -> R.string.form1_err_time
    Form1Error.DEPARTURE_BEFORE_ARRIVAL -> R.string.form1_err_departure_before_arrival
    Form1Error.INVALID_QUANTITY -> R.string.form1_err_quantity
    Form1Error.NO_PRODUCT_LINES -> R.string.form1_err_no_products
    Form1Error.SIGNATURE_REQUIRED -> R.string.form1_err_signatures
    Form1Error.PHOTO_REQUIRED -> R.string.form1_err_photos
}

/** Hilt-backed entry point for Form 1. */
@Composable
fun Form1Route(viewModel: Form1ViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()
    Form1Screen(
        state = state,
        onDonorNameChange = viewModel::onDonorNameChange,
        onDeliveryNoteChange = viewModel::onDeliveryNoteChange,
        onCollectNotesChange = viewModel::onCollectNotesChange,
        onToggleNoteAttached = viewModel::onToggleNoteAttached,
        onStampDeparture = viewModel::onStampDeparture,
        onToggleDonorSigned = viewModel::onToggleDonorSigned,
        onToggleCboSigned = viewModel::onToggleCboSigned,
        onToggleShot = viewModel::onToggleShot,
        onOpenAddProduct = viewModel::onOpenAddProduct,
        onRemoveProduct = viewModel::onRemoveProduct,
        onDraftCategoryChange = viewModel::onDraftCategoryChange,
        onDraftKgChange = viewModel::onDraftKgChange,
        onDraftNotesChange = viewModel::onDraftNotesChange,
        onConfirmAddProduct = viewModel::onConfirmAddProduct,
        onDismissAddProduct = viewModel::onDismissAddProduct,
        onSubmit = viewModel::onSubmit,
        onStartNew = viewModel::onStartNew
    )
}

@Composable
fun Form1Screen(
    state: Form1UiState,
    onDonorNameChange: (String) -> Unit,
    onDeliveryNoteChange: (String) -> Unit,
    onCollectNotesChange: (String) -> Unit,
    onToggleNoteAttached: () -> Unit,
    onStampDeparture: () -> Unit,
    onToggleDonorSigned: () -> Unit,
    onToggleCboSigned: () -> Unit,
    onToggleShot: (Int) -> Unit,
    onOpenAddProduct: () -> Unit,
    onRemoveProduct: (String) -> Unit,
    onDraftCategoryChange: (String) -> Unit,
    onDraftKgChange: (String) -> Unit,
    onDraftNotesChange: (String) -> Unit,
    onConfirmAddProduct: () -> Unit,
    onDismissAddProduct: () -> Unit,
    onSubmit: () -> Unit,
    onStartNew: () -> Unit
) {
    if (state.submitted) {
        SuccessContent(onStartNew)
        return
    }
    val form = state.form
    val errors = state.errors

    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag(ScreenTags.FORM1)
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Text(stringResource(R.string.form1_title), style = MaterialTheme.typography.headlineSmall)

        // Times
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TimeBox(R.string.form1_arrival, form.arrivalTime, Modifier.weight(1f))
                TimeBox(
                    R.string.form1_departure,
                    form.departureTime ?: stringResource(R.string.form1_departure_none),
                    Modifier.weight(1f)
                )
            }
            FieldError(Form1Field.DEPARTURE, errors)
            FieldError(Form1Field.ARRIVAL, errors)
            OutlinedButton(
                onClick = onStampDeparture,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag(Form1Tags.STAMP_DEPARTURE)
            ) { Text(stringResource(R.string.form1_stamp_departure), fontSize = 16.sp) }
        }

        // Products
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel(stringResource(R.string.form1_products))
            form.productLines.forEach { line ->
                ProductLineCard(line, onRemove = { onRemoveProduct(line.id) })
            }
            OutlinedButton(
                onClick = onOpenAddProduct,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag(Form1Tags.ADD_PRODUCT)
            ) { Text("+ " + stringResource(R.string.form1_add_product), fontSize = 16.sp) }
            FieldError(Form1Field.PRODUCTS, errors)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .roundedBackground(Ink)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.form1_total_weight), color = Color.White, fontSize = 15.sp)
                Text(
                    Form1Validator.formatKg(form.totalKg) + " kg",
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        // Donor name
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionLabel(stringResource(R.string.form1_donor_name))
            OutlinedTextField(
                value = form.donorName,
                onValueChange = onDonorNameChange,
                placeholder = { Text(stringResource(R.string.form1_donor_name_hint)) },
                singleLine = true,
                isError = Form1Field.DONOR_NAME in errors,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(Form1Tags.DONOR_NAME)
            )
            FieldError(Form1Field.DONOR_NAME, errors)
        }

        // Signatures
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel(stringResource(R.string.form1_signatures))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ToggleCard(
                    title = stringResource(R.string.form1_sign_donor),
                    status = stringResource(if (form.donorSigned) R.string.form1_signed else R.string.form1_tap_to_sign),
                    done = form.donorSigned,
                    onClick = onToggleDonorSigned,
                    modifier = Modifier
                        .weight(1f)
                        .testTag(Form1Tags.SIGN_DONOR)
                )
                ToggleCard(
                    title = stringResource(R.string.form1_sign_cbo),
                    status = stringResource(if (form.cboSigned) R.string.form1_signed else R.string.form1_tap_to_sign),
                    done = form.cboSigned,
                    onClick = onToggleCboSigned,
                    modifier = Modifier
                        .weight(1f)
                        .testTag(Form1Tags.SIGN_CBO)
                )
            }
            FieldError(Form1Field.SIGNATURES, errors)
        }

        // Photos
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel(stringResource(R.string.form1_photos))
            form.shots.chunked(2).forEachIndexed { row, pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    pair.forEachIndexed { col, taken ->
                        val index = row * 2 + col
                        ToggleCard(
                            title = stringResource(R.string.form1_photo_shot, index + 1),
                            status = stringResource(if (taken) R.string.form1_photo_taken else R.string.form1_photo_tap),
                            done = taken,
                            onClick = { onToggleShot(index) },
                            modifier = Modifier
                                .weight(1f)
                                .testTag(Form1Tags.PHOTO_PREFIX + index)
                        )
                    }
                }
            }
            FieldError(Form1Field.PHOTOS, errors)
        }

        // Delivery note
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = form.deliveryNote,
                onValueChange = onDeliveryNoteChange,
                label = { Text(stringResource(R.string.form1_delivery_note)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(Form1Tags.DELIVERY_NOTE)
            )
            OutlinedButton(
                onClick = onToggleNoteAttached,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
            ) {
                Text(
                    stringResource(if (form.noteAttached) R.string.form1_note_attached else R.string.form1_note_attach),
                    fontSize = 16.sp
                )
            }
        }

        // Notes
        OutlinedTextField(
            value = form.collectNotes,
            onValueChange = onCollectNotesChange,
            label = { Text(stringResource(R.string.form1_notes)) },
            placeholder = { Text(stringResource(R.string.form1_notes_hint)) },
            minLines = 3,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(Form1Tags.NOTES)
        )

        if (errors.isNotEmpty()) {
            Text(
                stringResource(R.string.form1_fix_errors),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            )
        }
        if (state.saveFailed) {
            Text(
                stringResource(R.string.form1_save_failed),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .testTag(Form1Tags.SAVE_FAILED)
                    .semantics { liveRegion = LiveRegionMode.Polite }
            )
        }

        Button(
            onClick = onSubmit,
            enabled = !state.isSaving,
            colors = ButtonDefaults.buttonColors(containerColor = Yellow, contentColor = Ink),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .testTag(Form1Tags.SUBMIT)
        ) {
            if (state.isSaving) {
                CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp), strokeWidth = 2.dp, color = Ink)
                Text(stringResource(R.string.form1_saving), fontSize = 16.sp)
            } else {
                Text(stringResource(R.string.form1_submit), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }

    state.productDraft?.let { draft ->
        AddProductDialog(
            draft = draft,
            onCategoryChange = onDraftCategoryChange,
            onKgChange = onDraftKgChange,
            onNotesChange = onDraftNotesChange,
            onConfirm = onConfirmAddProduct,
            onDismiss = onDismissAddProduct
        )
    }
}

@Composable
private fun SuccessContent(onStartNew: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag(ScreenTags.FORM1)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            stringResource(R.string.form1_success_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.testTag(Form1Tags.SUCCESS)
        )
        Text(stringResource(R.string.form1_success_body))
        Button(
            onClick = onStartNew,
            colors = ButtonDefaults.buttonColors(containerColor = Yellow, contentColor = Ink),
            modifier = Modifier
                .heightIn(min = 48.dp)
                .testTag(Form1Tags.NEW_COLLECTION)
        ) { Text(stringResource(R.string.form1_new_collection), fontSize = 16.sp) }
    }
}

@Composable
private fun SectionLabel(text: String) =
    Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)

@Composable
private fun FieldError(field: Form1Field, errors: Map<Form1Field, Form1Error>) {
    val error = errors[field] ?: return
    Text(
        stringResource(error.message()),
        color = MaterialTheme.colorScheme.error,
        fontSize = 13.sp,
        modifier = Modifier
            .testTag(Form1Tags.error(field))
            .semantics { liveRegion = LiveRegionMode.Polite }
    )
}

@Composable
private fun TimeBox(@StringRes label: Int, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .roundedBackground(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(stringResource(label), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ProductLineCard(line: ProductLineInput, onRemove: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(line.category, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Text(
                    line.notes.ifBlank { stringResource(R.string.form1_no_notes) },
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text("${line.kg} kg", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
            TextButton(onClick = onRemove) { Text(stringResource(R.string.form1_remove_product)) }
        }
    }
}

@Composable
private fun ToggleCard(
    title: String,
    status: String,
    done: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(if (done) 2.dp else 1.dp, if (done) Color(0xFF2F5D3A) else MaterialTheme.colorScheme.outline),
        modifier = modifier.heightIn(min = 64.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text(status, fontSize = 13.sp, color = if (done) Color(0xFF2F5D3A) else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AddProductDialog(
    draft: ProductDraft,
    onCategoryChange: (String) -> Unit,
    onKgChange: (String) -> Unit,
    onNotesChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.form1_dialog_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                CategoryPicker(draft.category, onCategoryChange)
                OutlinedTextField(
                    value = draft.kg,
                    onValueChange = onKgChange,
                    label = { Text(stringResource(R.string.form1_dialog_kg)) },
                    singleLine = true,
                    isError = draft.kgError != null,
                    supportingText = draft.kgError?.let { { Text(stringResource(it.message())) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(Form1Tags.DRAFT_KG)
                )
                OutlinedTextField(
                    value = draft.notes,
                    onValueChange = onNotesChange,
                    label = { Text(stringResource(R.string.form1_dialog_notes)) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag(Form1Tags.DRAFT_CONFIRM)) {
                Text(stringResource(R.string.form1_dialog_add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.form1_dialog_cancel)) }
        }
    )
}

@Composable
private fun CategoryPicker(selected: String, onSelect: (String) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column {
        Text(stringResource(R.string.form1_dialog_category), fontSize = 13.sp)
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
        ) { Text(selected, fontSize = 16.sp) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            PRODUCT_CATEGORIES.forEach { category ->
                DropdownMenuItem(
                    text = { Text(category) },
                    onClick = {
                        onSelect(category)
                        expanded = false
                    }
                )
            }
        }
    }
}

private fun Modifier.roundedBackground(color: Color): Modifier =
    this.then(Modifier.background(color, RoundedCornerShape(12.dp)))
