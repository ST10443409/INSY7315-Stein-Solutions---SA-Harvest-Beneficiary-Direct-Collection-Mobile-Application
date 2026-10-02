package com.example.client.ui.cbo

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.client.data.local.entity.AttachmentKind

// The Hilt-backed entry points of the screens that share one Form1ViewModel: the collection form, the signature pad, the
// photos screen and the receipt. The navigation graph gives all four the same ViewModel, so a signature drawn on one
// screen is on the form when the user comes back.

/** The signature pad for one signature of the collection being filled in. Accepting stores it and returns. */
@Composable
fun SignatureRoute(kind: AttachmentKind, onBack: () -> Unit, viewModel: Form1ViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()
    SignaturePadScreen(
        kind = kind,
        donorName = state.form.donorName,
        onAccept = { png ->
            viewModel.onSignatureCaptured(kind, png)
            onBack()
        },
        onBack = onBack
    )
}

/** The four donation photo slots of the collection being filled in. */
@Composable
fun PhotosRoute(onBack: () -> Unit, viewModel: Form1ViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()
    PhotosScreen(
        form = state.form,
        actions = AttachmentActions(
            onPhotoPicked = viewModel::onPhotoPicked,
            onNewCaptureTarget = viewModel::newCaptureTarget,
            onCaptureResult = viewModel::onCaptureResult,
            onRemove = viewModel::onRemoveAttachment
        ),
        onBack = onBack
    )
}

/**
 * The receipt for the collection that was just saved. Leaving it, by either button or by Back, clears the form so the
 * next collection starts empty.
 */
@Composable
fun CollectionDoneRoute(
    variant: DoneVariant,
    onPrimary: () -> Unit,
    onSecondary: () -> Unit,
    viewModel: Form1ViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val receipt = state.receipt ?: return
    BackHandler {
        viewModel.onStartNew()
        onSecondary()
    }
    CollectionDoneScreen(
        receipt = receipt,
        variant = variant,
        onPrimary = {
            viewModel.onStartNew()
            onPrimary()
        },
        onSecondary = {
            viewModel.onStartNew()
            onSecondary()
        }
    )
}
