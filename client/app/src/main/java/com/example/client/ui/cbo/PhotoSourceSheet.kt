package com.example.client.ui.cbo

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import com.example.client.R
import com.example.client.data.attachments.CaptureTarget
import com.example.client.ui.components.FilledPillButton
import com.example.client.ui.components.OutlinePillButton
import com.example.client.ui.theme.Figtree
import com.example.client.ui.theme.Poppins
import com.example.client.ui.theme.SaColors

object PhotoSourceTags {
    const val SHEET = "photo_source_sheet"
    const val TAKE = "photo_source_take"
    const val CHOOSE = "photo_source_choose"
    const val REMOVE = "photo_source_remove"
    const val NOTICE = "photo_source_notice"
}

/** What the photo source sheet does with a picture, handed in by whoever owns the form's state. */
class AttachmentActions(
    /** The user picked a picture from the gallery for the slot. */
    val onPhotoPicked: (AttachmentSlot, String) -> Unit,
    /** The camera is about to open for the slot; returns the file it should write to. */
    val onNewCaptureTarget: (AttachmentSlot) -> CaptureTarget,
    /** The camera came back; false if the user backed out. */
    val onCaptureResult: (Boolean) -> Unit,
    val onRemove: (AttachmentSlot) -> Unit
)

/**
 * Lets the user add a photo to [slot]: take one with the camera (asking for camera permission first, the moment they
 * choose it) or pick one from the gallery, which needs no permission. Dismisses itself once a photo is on its way.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoSourceSheet(
    slot: AttachmentSlot,
    hasPhoto: Boolean,
    actions: AttachmentActions,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var notice by remember { mutableStateOf<Int?>(null) }

    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        actions.onCaptureResult(success)
        onDismiss()
    }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            actions.onPhotoPicked(slot, uri.toString())
            onDismiss()
        }
    }

    fun launchCamera() {
        val target = actions.onNewCaptureTarget(slot)
        try {
            takePicture.launch(Uri.parse(target.uri))
        } catch (e: ActivityNotFoundException) {
            actions.onCaptureResult(false)
            notice = R.string.photo_no_camera
        }
    }

    val askForCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCamera() else notice = R.string.photo_permission_denied
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 28.dp),
        containerColor = SaColors.Surface,
        scrimColor = SaColors.inkAlpha(0.42f),
        modifier = Modifier.testTag(PhotoSourceTags.SHEET)
    ) {
        Column(
            modifier = Modifier.padding(22.dp, 4.dp, 22.dp, 26.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                stringResource(R.string.photo_source_title),
                fontFamily = Poppins, fontWeight = FontWeight.SemiBold, fontSize = 21.sp, color = SaColors.Ink,
                modifier = Modifier.padding(bottom = 6.dp)
            )
            notice?.let {
                Text(
                    stringResource(it),
                    fontFamily = Figtree, fontSize = 12.5.sp, lineHeight = 19.sp, color = SaColors.TagErrorText,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(SaColors.TagErrorBg)
                        .padding(14.dp)
                        .testTag(PhotoSourceTags.NOTICE)
                        .semantics { liveRegion = LiveRegionMode.Polite }
                )
            }
            FilledPillButton(
                onClick = {
                    notice = null
                    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                        PackageManager.PERMISSION_GRANTED
                    if (granted) launchCamera() else askForCamera.launch(Manifest.permission.CAMERA)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(PhotoSourceTags.TAKE),
                contentPadding = PaddingValues(15.dp)
            ) {
                Text(stringResource(R.string.photo_take), fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 14.5.sp, color = SaColors.Ink)
            }
            OutlinePillButton(
                onClick = {
                    notice = null
                    pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(PhotoSourceTags.CHOOSE),
                contentPadding = PaddingValues(15.dp)
            ) {
                Text(stringResource(R.string.photo_choose), fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 14.5.sp, color = SaColors.Ink)
            }
            if (hasPhoto) {
                OutlinePillButton(
                    onClick = {
                        actions.onRemove(slot)
                        onDismiss()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(PhotoSourceTags.REMOVE),
                    contentPadding = PaddingValues(15.dp)
                ) {
                    Text(stringResource(R.string.photo_remove), fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 14.5.sp, color = SaColors.TagErrorText)
                }
            }
        }
    }
}
