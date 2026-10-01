package com.example.client.ui.cbo

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.client.R
import com.example.client.ui.components.FilledPillButton
import com.example.client.ui.components.OutlinePillButton
import com.example.client.ui.components.SaDashedActionButton
import com.example.client.ui.components.SaInputField
import com.example.client.ui.components.SaSelectField
import com.example.client.ui.components.SaTextArea
import com.example.client.ui.components.ScreenHeader
import com.example.client.ui.components.dashedBorder
import com.example.client.ui.placeholder.ScreenTags
import com.example.client.ui.theme.CBOCollectorTheme
import com.example.client.ui.theme.Figtree
import com.example.client.ui.theme.GlyphPaths
import com.example.client.ui.theme.Poppins
import com.example.client.ui.theme.SaColors
import com.example.client.ui.theme.StrokeIcon

// Visual design follows the CBO Collector UI demo (CollectScreen, PhotosScreen, AddProductSheet, DoneScreen).

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
) = CBOCollectorTheme {
    if (state.submitted) {
        SuccessContent(onStartNew)
    } else {
    val form = state.form
    val errors = state.errors

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SaColors.Surface)
            .testTag(ScreenTags.FORM1)
    ) {
        ScreenHeader(
            title = stringResource(R.string.form1_title),
            modifier = Modifier.padding(20.dp, 18.dp, 20.dp, 14.dp)
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(20.dp, 0.dp, 20.dp, 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Times
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatBox(
                        label = stringResource(R.string.form1_arrival),
                        value = form.arrivalTime,
                        containerColor = SaColors.SurfaceAlt,
                        textColor = SaColors.Ink,
                        modifier = Modifier.weight(1f)
                    )
                    StatBox(
                        label = stringResource(R.string.form1_departure),
                        value = form.departureTime ?: "—",
                        containerColor = if (form.departureTime != null) SaColors.SurfaceAlt else SaColors.AppBg,
                        textColor = if (form.departureTime != null) SaColors.Ink else SaColors.MutedLight,
                        modifier = Modifier.weight(1f)
                    )
                }
                FieldError(Form1Field.ARRIVAL, errors)
                FieldError(Form1Field.DEPARTURE, errors)
            }

            // Products
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        stringResource(R.string.form1_products),
                        fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp, color = SaColors.Ink
                    )
                    Text(
                        if (form.productLines.size == 1) stringResource(R.string.form1_line_count_one)
                        else stringResource(R.string.form1_lines_count, form.productLines.size),
                        fontFamily = Figtree, fontSize = 12.sp, color = SaColors.MutedLight
                    )
                }
                form.productLines.forEach { line -> ProductLineCard(line) { onRemoveProduct(line.id) } }
                SaDashedActionButton(
                    onClick = onOpenAddProduct,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(Form1Tags.ADD_PRODUCT)
                ) {
                    StrokeIcon(pathData = GlyphPaths.Plus, tint = SaColors.LinkGold, modifier = Modifier.size(17.dp))
                    Text(
                        stringResource(R.string.form1_add_product),
                        fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = SaColors.Muted,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
                FieldError(Form1Field.PRODUCTS, errors)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(SaColors.Ink)
                        .padding(horizontal = 18.dp, vertical = 15.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.form1_total_weight),
                        fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp, color = SaColors.Cream
                    )
                    Text(
                        Form1Validator.formatKg(form.totalKg) + " kg",
                        fontFamily = Poppins, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, color = SaColors.Cream
                    )
                }
            }

            // Donor name
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                FieldLabel(stringResource(R.string.form1_donor_name), required = true)
                SaInputField(
                    value = form.donorName,
                    onValueChange = onDonorNameChange,
                    placeholder = stringResource(R.string.form1_donor_name_hint),
                    isError = Form1Field.DONOR_NAME in errors,
                    fieldTestTag = Form1Tags.DONOR_NAME
                )
                FieldError(Form1Field.DONOR_NAME, errors)
            }

            // Signatures
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FieldLabel(stringResource(R.string.form1_signatures), required = true)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SignatureButton(
                        title = stringResource(R.string.form1_sign_donor),
                        signed = form.donorSigned,
                        onClick = onToggleDonorSigned,
                        modifier = Modifier
                            .weight(1f)
                            .testTag(Form1Tags.SIGN_DONOR)
                    )
                    SignatureButton(
                        title = stringResource(R.string.form1_sign_cbo),
                        signed = form.cboSigned,
                        onClick = onToggleCboSigned,
                        modifier = Modifier
                            .weight(1f)
                            .testTag(Form1Tags.SIGN_CBO)
                    )
                }
                FieldError(Form1Field.SIGNATURES, errors)
            }

            // Photos
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FieldLabel(stringResource(R.string.form1_photos), required = true)
                Text(
                    stringResource(R.string.form1_photos_progress, form.shots.count { it }),
                    fontFamily = Figtree, fontSize = 12.sp, color = SaColors.MutedLight
                )
                val labels = stringArrayResource(R.array.form1_photo_labels)
                form.shots.chunked(2).forEachIndexed { row, pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        pair.forEachIndexed { col, taken ->
                            val index = row * 2 + col
                            PhotoShot(
                                label = labels.getOrElse(index) { "" },
                                taken = taken,
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
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                FieldLabel(stringResource(R.string.form1_delivery_note))
                SaInputField(
                    value = form.deliveryNote,
                    onValueChange = onDeliveryNoteChange,
                    placeholder = stringResource(R.string.form1_delivery_note),
                    fieldTestTag = Form1Tags.DELIVERY_NOTE
                )
                OutlinePillButton(
                    onClick = onToggleNoteAttached,
                    modifier = Modifier.padding(top = 4.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    Text(
                        stringResource(if (form.noteAttached) R.string.form1_note_attached else R.string.form1_note_attach),
                        fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = SaColors.Ink
                    )
                }
            }

            // Notes
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                FieldLabel(stringResource(R.string.form1_notes))
                SaTextArea(
                    value = form.collectNotes,
                    onValueChange = onCollectNotesChange,
                    placeholder = stringResource(R.string.form1_notes_hint),
                    minLines = 3,
                    fieldTestTag = Form1Tags.NOTES
                )
            }

            OutlinePillButton(
                onClick = onStampDeparture,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(Form1Tags.STAMP_DEPARTURE),
                contentPadding = PaddingValues(15.dp)
            ) {
                Text(
                    form.departureTime?.let { stringResource(R.string.form1_departure_stamped, it) }
                        ?: stringResource(R.string.form1_stamp_departure),
                    fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 14.5.sp, color = SaColors.Ink
                )
            }

            if (errors.isNotEmpty()) {
                Notice(stringResource(R.string.form1_fix_errors), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            if (state.saveFailed) {
                Notice(
                    stringResource(R.string.form1_save_failed),
                    Modifier
                        .testTag(Form1Tags.SAVE_FAILED)
                        .semantics { liveRegion = LiveRegionMode.Polite }
                )
            }

            FilledPillButton(
                onClick = onSubmit,
                enabled = !state.isSaving,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(Form1Tags.SUBMIT),
                contentPadding = PaddingValues(16.dp)
            ) {
                Text(
                    stringResource(if (state.isSaving) R.string.form1_saving else R.string.form1_submit),
                    fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
                    color = if (state.isSaving) SaColors.Muted else SaColors.Ink
                )
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
}

/** Mirrors the demo's DoneScreen: yellow check, big Poppins title, short explanation. */
@Composable
private fun SuccessContent(onStartNew: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SaColors.Surface)
            .testTag(ScreenTags.FORM1)
            .verticalScroll(rememberScrollState())
            .padding(26.dp, 32.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(SaColors.Yellow),
            contentAlignment = Alignment.Center
        ) {
            StrokeIcon(pathData = GlyphPaths.Check, tint = SaColors.Ink, strokeWidth = 2.75f, modifier = Modifier.size(34.dp))
        }
        Text(
            stringResource(R.string.form1_success_title),
            fontFamily = Poppins, fontWeight = FontWeight.SemiBold, fontSize = 30.sp, color = SaColors.Ink,
            modifier = Modifier
                .padding(top = 24.dp, bottom = 10.dp)
                .testTag(Form1Tags.SUCCESS)
        )
        Text(
            stringResource(R.string.form1_success_body),
            fontFamily = Figtree, fontSize = 14.5.sp, lineHeight = 23.sp, color = SaColors.Muted
        )
        FilledPillButton(
            onClick = onStartNew,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 28.dp)
                .testTag(Form1Tags.NEW_COLLECTION),
            contentPadding = PaddingValues(16.dp)
        ) {
            Text(
                stringResource(R.string.form1_new_collection),
                fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = SaColors.Ink
            )
        }
    }
}

@Composable
private fun FieldLabel(text: String, required: Boolean = false) {
    Row {
        Text(text, fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp, color = SaColors.Ink)
        if (required) {
            Text(" *", fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp, color = SaColors.YellowDark)
        }
    }
}

@Composable
private fun FieldError(field: Form1Field, errors: Map<Form1Field, Form1Error>) {
    val error = errors[field] ?: return
    Text(
        stringResource(error.message()),
        fontFamily = Figtree, fontWeight = FontWeight.Medium, fontSize = 12.5.sp, color = SaColors.Error,
        modifier = Modifier
            .testTag(Form1Tags.error(field))
            .semantics { liveRegion = LiveRegionMode.Polite }
    )
}

/** A tinted callout, like the demo's yellow hint on the photos screen, in red for problems. */
@Composable
private fun Notice(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        fontFamily = Figtree, fontSize = 12.5.sp, lineHeight = 19.sp, color = SaColors.TagErrorText,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SaColors.TagErrorBg)
            .padding(14.dp)
    )
}

@Composable
private fun StatBox(label: String, value: String, containerColor: Color, textColor: Color, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(containerColor)
            .padding(16.dp, 13.dp)
    ) {
        Text(
            label.uppercase(),
            fontFamily = Figtree, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 1.1.sp,
            color = textColor.copy(alpha = 0.7f)
        )
        Text(
            value,
            fontFamily = Poppins, fontWeight = FontWeight.SemiBold, fontSize = 21.sp, color = textColor,
            modifier = Modifier.padding(top = 3.dp)
        )
    }
}

@Composable
private fun ProductLineCard(line: ProductLineInput, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SaColors.White)
            .border(1.dp, SaColors.inkAlpha(0.12f), RoundedCornerShape(12.dp))
            .padding(16.dp, 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(line.category, fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = SaColors.Ink)
            Text(
                line.notes.ifBlank { stringResource(R.string.form1_no_notes) },
                fontFamily = Figtree, fontSize = 11.5.sp, color = SaColors.MutedLight
            )
        }
        Text(
            "${line.kg} kg",
            fontFamily = Poppins, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, color = SaColors.LinkGold,
            modifier = Modifier.padding(horizontal = 12.dp)
        )
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(SaColors.AppBg)
                .clickable(onClickLabel = stringResource(R.string.form1_remove_product), onClick = onRemove),
            contentAlignment = Alignment.Center
        ) {
            Text("×", fontFamily = Figtree, fontSize = 15.sp, color = SaColors.Muted)
        }
    }
}

@Composable
private fun SignatureButton(title: String, signed: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .background(if (signed) SaColors.SurfaceAlt else SaColors.White, shape)
            .let {
                if (signed) it.border(1.dp, SaColors.Divider, shape)
                else it.dashedBorder(SaColors.DashedBorder, cornerRadius = 12.dp)
            }
            .clickable(onClick = onClick)
            .padding(16.dp, 12.dp)
    ) {
        Text(title, fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = SaColors.Ink)
        Text(
            stringResource(if (signed) R.string.form1_signed else R.string.form1_tap_to_sign),
            fontFamily = Figtree, fontSize = 11.5.sp, color = SaColors.Ink.copy(alpha = 0.8f),
            modifier = Modifier.padding(top = 3.dp)
        )
    }
}

/** One photo slot, styled like a tile on the demo's photos screen. */
@Composable
private fun PhotoShot(label: String, taken: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(SaColors.White)
            .border(1.dp, SaColors.inkAlpha(0.08f), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
                .background(
                    Brush.linearGradient(
                        colors = if (taken) listOf(SaColors.SurfaceAlt, SaColors.Divider) else listOf(SaColors.SurfaceAlt, SaColors.AppBg)
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            if (taken) StrokeIcon(pathData = GlyphPaths.Check, tint = SaColors.TagOkText, modifier = Modifier.size(24.dp))
        }
        Column(modifier = Modifier.padding(13.dp, 11.dp)) {
            Text(label, fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp, color = SaColors.Ink)
            Text(
                stringResource(if (taken) R.string.form1_photo_taken else R.string.form1_photo_tap),
                fontFamily = Figtree, fontSize = 11.sp, color = if (taken) SaColors.TagOkText else SaColors.Faint,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

/** The demo's "Add product" sheet, shown as a rounded dialog. */
@Composable
private fun AddProductDialog(
    draft: ProductDraft,
    onCategoryChange: (String) -> Unit,
    onKgChange: (String) -> Unit,
    onNotesChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(SaColors.Surface)
                .verticalScroll(rememberScrollState())
                .padding(22.dp, 22.dp, 22.dp, 20.dp)
        ) {
            Text(
                stringResource(R.string.form1_dialog_title),
                fontFamily = Poppins, fontWeight = FontWeight.SemiBold, fontSize = 21.sp, color = SaColors.Ink,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(
                        stringResource(R.string.form1_dialog_category),
                        fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = SaColors.Ink
                    )
                    SaSelectField(value = draft.category, options = PRODUCT_CATEGORIES, onSelect = onCategoryChange)
                }
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(
                        stringResource(R.string.form1_dialog_kg),
                        fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = SaColors.Ink
                    )
                    SaInputField(
                        value = draft.kg,
                        onValueChange = onKgChange,
                        placeholder = "0.0",
                        keyboardType = KeyboardType.Decimal,
                        isError = draft.kgError != null,
                        fieldTestTag = Form1Tags.DRAFT_KG,
                        modifier = Modifier.width(160.dp)
                    )
                    draft.kgError?.let {
                        Text(
                            stringResource(it.message()),
                            fontFamily = Figtree, fontWeight = FontWeight.Medium, fontSize = 12.5.sp, color = SaColors.Error
                        )
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(
                        stringResource(R.string.form1_dialog_notes),
                        fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = SaColors.Ink
                    )
                    SaTextArea(value = draft.notes, onValueChange = onNotesChange, minLines = 2)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinePillButton(onClick = onDismiss, contentPadding = PaddingValues(vertical = 14.dp, horizontal = 22.dp)) {
                        Text(
                            stringResource(R.string.form1_dialog_cancel),
                            fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = SaColors.Ink
                        )
                    }
                    FilledPillButton(
                        onClick = onConfirm,
                        modifier = Modifier
                            .weight(1f)
                            .testTag(Form1Tags.DRAFT_CONFIRM),
                        contentPadding = PaddingValues(14.dp)
                    ) {
                        Text(
                            stringResource(R.string.form1_dialog_add_to_collection),
                            fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 14.5.sp, color = SaColors.Ink
                        )
                    }
                }
            }
        }
    }
}
