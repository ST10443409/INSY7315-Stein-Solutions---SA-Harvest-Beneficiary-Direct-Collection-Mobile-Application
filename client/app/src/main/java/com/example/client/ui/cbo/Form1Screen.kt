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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
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
import com.example.client.data.local.entity.AttachmentKind
import com.example.client.ui.components.AttachmentImage
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

// Visual design follows the CBO Collector UI demo (CollectScreen, AddProductSheet). The demo's Sign, Photos and Done
// screens are separate screens here too (SignaturePadScreen, PhotosScreen, CollectionDoneScreen).

object Form1Tags {
    const val DONOR_NAME = "form1_donor_name"
    const val DELIVERY_NOTE = "form1_delivery_note"
    const val NOTES = "form1_notes"
    const val ADD_PRODUCT = "form1_add_product"
    const val DRAFT_KG = "form1_draft_kg"
    const val DRAFT_CONFIRM = "form1_draft_confirm"
    const val SIGN_DONOR = "form1_sign_donor"
    const val SIGN_CBO = "form1_sign_cbo"
    const val PHOTOS_ROW = "form1_photos_row"
    const val NOTE_PHOTO = "form1_note_photo"
    const val STAMP_DEPARTURE = "form1_stamp_departure"
    const val SUBMIT = "form1_submit"
    const val SAVE_FAILED = "form1_save_failed"
    const val ATTACHMENT_FAILED = "form1_attachment_failed"
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

/**
 * Hilt-backed entry point for Form 1. [onSubmitted] is called once the collection is saved (the receipt screen takes
 * over from there); [onBack] is null where the form is a top-level tab with nothing behind it.
 */
@Composable
fun Form1Route(
    onOpenSignature: (AttachmentKind) -> Unit = {},
    onOpenPhotos: () -> Unit = {},
    onSubmitted: () -> Unit = {},
    onBack: (() -> Unit)? = null,
    viewModel: Form1ViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    LaunchedEffect(state.submitted) { if (state.submitted) onSubmitted() }
    Form1Screen(
        state = state,
        onDonorNameChange = viewModel::onDonorNameChange,
        onDeliveryNoteChange = viewModel::onDeliveryNoteChange,
        onCollectNotesChange = viewModel::onCollectNotesChange,
        onStampDeparture = viewModel::onStampDeparture,
        onOpenSignature = onOpenSignature,
        onOpenPhotos = onOpenPhotos,
        attachmentActions = AttachmentActions(
            onPhotoPicked = viewModel::onPhotoPicked,
            onNewCaptureTarget = viewModel::newCaptureTarget,
            onCaptureResult = viewModel::onCaptureResult,
            onRemove = viewModel::onRemoveAttachment
        ),
        onOpenAddProduct = viewModel::onOpenAddProduct,
        onRemoveProduct = viewModel::onRemoveProduct,
        onDraftCategoryChange = viewModel::onDraftCategoryChange,
        onDraftKgChange = viewModel::onDraftKgChange,
        onDraftNotesChange = viewModel::onDraftNotesChange,
        onConfirmAddProduct = viewModel::onConfirmAddProduct,
        onDismissAddProduct = viewModel::onDismissAddProduct,
        onSubmit = viewModel::onSubmit,
        onBack = onBack
    )
}

@Composable
fun Form1Screen(
    state: Form1UiState,
    onDonorNameChange: (String) -> Unit,
    onDeliveryNoteChange: (String) -> Unit,
    onCollectNotesChange: (String) -> Unit,
    onStampDeparture: () -> Unit,
    onOpenSignature: (AttachmentKind) -> Unit,
    onOpenPhotos: () -> Unit,
    attachmentActions: AttachmentActions,
    onOpenAddProduct: () -> Unit,
    onRemoveProduct: (String) -> Unit,
    onDraftCategoryChange: (String) -> Unit,
    onDraftKgChange: (String) -> Unit,
    onDraftNotesChange: (String) -> Unit,
    onConfirmAddProduct: () -> Unit,
    onDismissAddProduct: () -> Unit,
    onSubmit: () -> Unit,
    onBack: (() -> Unit)? = null
) = CBOCollectorTheme {
    val form = state.form
    val errors = state.errors
    // Whether the delivery-note photo sheet is open; saved so the camera app returning does not close it.
    var noteSheetOpen by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SaColors.Surface)
            .testTag(ScreenTags.FORM1)
    ) {
        ScreenHeader(
            title = stringResource(R.string.form1_title),
            onBack = onBack,
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
                        signaturePath = form.attachments[AttachmentSlot.DonorSignature]?.path,
                        onClick = { onOpenSignature(AttachmentKind.DONOR_SIGNATURE) },
                        modifier = Modifier
                            .weight(1f)
                            .testTag(Form1Tags.SIGN_DONOR)
                    )
                    SignatureButton(
                        title = stringResource(R.string.form1_sign_cbo),
                        signaturePath = form.attachments[AttachmentSlot.CboSignature]?.path,
                        onClick = { onOpenSignature(AttachmentKind.CBO_SIGNATURE) },
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
                val shotCount = form.shots.count { it }
                val firstPhoto = form.attachments[AttachmentSlot.photo(form.shots.indexOfFirst { it }.coerceAtLeast(0))]
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(SaColors.White)
                        .border(1.dp, SaColors.inkAlpha(0.12f), RoundedCornerShape(12.dp))
                        .clickable(onClick = onOpenPhotos)
                        .padding(16.dp, 14.dp)
                        .testTag(Form1Tags.PHOTOS_ROW),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Brush.linearGradient(colors = listOf(SaColors.AppBg, SaColors.Divider)))
                    ) {
                        if (shotCount > 0) AttachmentImage(path = firstPhoto?.path, modifier = Modifier.fillMaxSize(), maxEdgePx = 160)
                    }
                    Column(modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp)) {
                        Text(
                            if (shotCount > 0) stringResource(R.string.form1_photos_row_count, shotCount)
                            else stringResource(R.string.form1_photos_row_empty),
                            fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = SaColors.Ink
                        )
                        Text(
                            stringResource(R.string.form1_photos_row_hint),
                            fontFamily = Figtree, fontSize = 11.5.sp, color = SaColors.MutedLight
                        )
                    }
                    StrokeIcon(pathData = GlyphPaths.ChevronRight, tint = SaColors.Faint, modifier = Modifier.size(18.dp))
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
                val notePhoto = form.attachments[AttachmentSlot.DeliveryNote]
                if (notePhoto != null) {
                    AttachmentImage(
                        path = notePhoto.path,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(SaColors.White)
                            .border(1.dp, SaColors.inkAlpha(0.12f), RoundedCornerShape(12.dp))
                    )
                }
                OutlinePillButton(
                    onClick = { noteSheetOpen = true },
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .testTag(Form1Tags.NOTE_PHOTO),
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
            if (state.attachmentFailed) {
                Notice(
                    stringResource(R.string.form1_attachment_failed),
                    Modifier
                        .testTag(Form1Tags.ATTACHMENT_FAILED)
                        .semantics { liveRegion = LiveRegionMode.Polite }
                )
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
        AddProductSheet(
            draft = draft,
            onCategoryChange = onDraftCategoryChange,
            onKgChange = onDraftKgChange,
            onNotesChange = onDraftNotesChange,
            onConfirm = onConfirmAddProduct,
            onDismiss = onDismissAddProduct
        )
    }
    if (noteSheetOpen) {
        PhotoSourceSheet(
            slot = AttachmentSlot.DeliveryNote,
            hasPhoto = AttachmentSlot.DeliveryNote in form.attachments,
            actions = attachmentActions,
            onDismiss = { noteSheetOpen = false }
        )
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

/** Opens the signature pad. Once signed it shows the signature itself, so the collector can see what was captured. */
@Composable
private fun SignatureButton(title: String, signaturePath: String?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(12.dp)
    val signed = signaturePath != null
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
        if (signed) {
            AttachmentImage(
                path = signaturePath,
                contentScale = ContentScale.Fit,
                maxEdgePx = 400,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(RoundedCornerShape(8.dp))
            )
        }
    }
}

/** The demo's "Add product" sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddProductSheet(
    draft: ProductDraft,
    onCategoryChange: (String) -> Unit,
    onKgChange: (String) -> Unit,
    onNotesChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 28.dp),
        containerColor = SaColors.Surface,
        scrimColor = SaColors.inkAlpha(0.42f)
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(22.dp, 4.dp, 22.dp, 26.dp)
        ) {
            Text(
                stringResource(R.string.form1_dialog_title),
                fontFamily = Poppins, fontWeight = FontWeight.SemiBold, fontSize = 21.sp, color = SaColors.Ink,
                modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
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
