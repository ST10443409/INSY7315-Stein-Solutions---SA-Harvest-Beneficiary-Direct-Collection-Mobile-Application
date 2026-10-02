package com.example.client.ui.cbo

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.client.R
import com.example.client.ui.components.AttachmentImage
import com.example.client.ui.components.FilledPillButton
import com.example.client.ui.components.ScreenHeader
import com.example.client.ui.theme.CBOCollectorTheme
import com.example.client.ui.theme.Figtree
import com.example.client.ui.theme.GlyphPaths
import com.example.client.ui.theme.SaColors
import com.example.client.ui.theme.StrokeIcon

object PhotosTags {
    const val SCREEN = "screen_photos"
    const val TAKE_NEXT = "photos_take_next"
    fun tile(index: Int) = "photos_tile_$index"
}

/**
 * The demo's Photos screen: four labelled slots for the donation photos. Tapping a slot lets the user take a photo or
 * choose one, and a captured slot shows the photo itself.
 */
@Composable
fun PhotosScreen(
    form: Form1FormState,
    actions: AttachmentActions,
    onBack: () -> Unit
) = CBOCollectorTheme {
    val labels = stringArrayResource(R.array.form1_photo_labels)
    val captured = form.shots.count { it }
    val firstEmpty = form.shots.indexOfFirst { !it }
    // Which slot the source sheet is open for; saved so a rotation (or the camera app returning) keeps it.
    var sheetSlot by rememberSaveable { mutableStateOf<Int?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SaColors.Surface)
            .verticalScroll(rememberScrollState())
            .padding(20.dp, 20.dp, 20.dp, 26.dp)
            .testTag(PhotosTags.SCREEN)
    ) {
        ScreenHeader(
            title = stringResource(R.string.photos_title),
            onBack = onBack,
            subtitle = stringResource(R.string.photos_subtitle, captured),
            modifier = Modifier.padding(bottom = 16.dp)
        )
        (0 until PHOTO_SHOT_COUNT).chunked(2).forEach { rowIndexes ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                rowIndexes.forEach { index ->
                    val photo = form.attachments[AttachmentSlot.photo(index)]
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(SaColors.White)
                            .clickable { sheetSlot = index }
                            .testTag(PhotosTags.tile(index))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(118.dp)
                                .background(
                                    Brush.linearGradient(
                                        colors = if (photo != null) listOf(SaColors.SurfaceAlt, SaColors.Divider)
                                        else listOf(SaColors.SurfaceAlt, SaColors.AppBg)
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            if (photo != null) {
                                AttachmentImage(path = photo.path, modifier = Modifier.fillMaxSize(), contentDescription = labels.getOrElse(index) { "" })
                            } else {
                                StrokeIcon(pathData = GlyphPaths.NavCamera, tint = SaColors.Faint, modifier = Modifier.size(28.dp))
                            }
                        }
                        Column(modifier = Modifier.padding(13.dp, 11.dp)) {
                            Text(labels.getOrElse(index) { "" }, fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp, color = SaColors.Ink)
                            Text(
                                stringResource(if (photo != null) R.string.form1_photo_taken else R.string.form1_photo_tap),
                                fontFamily = Figtree, fontSize = 11.sp,
                                color = if (photo != null) SaColors.TagOkText else SaColors.Faint,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                    }
                }
            }
        }
        FilledPillButton(
            onClick = { if (firstEmpty >= 0) sheetSlot = firstEmpty },
            enabled = firstEmpty >= 0,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                .testTag(PhotosTags.TAKE_NEXT),
            contentPadding = PaddingValues(15.dp)
        ) {
            Text(
                stringResource(if (firstEmpty >= 0) R.string.photos_take_next else R.string.photos_all_taken),
                fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 14.5.sp,
                color = if (firstEmpty >= 0) SaColors.Ink else SaColors.Muted
            )
        }
        Text(
            stringResource(R.string.photos_hint),
            fontFamily = Figtree, fontSize = 12.5.sp, lineHeight = 19.sp, color = SaColors.LinkGold,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 14.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(SaColors.YellowTickBg)
                .padding(14.dp, 14.dp, 18.dp, 14.dp)
        )
    }

    sheetSlot?.let { index ->
        val slot = AttachmentSlot.photo(index)
        PhotoSourceSheet(
            slot = slot,
            hasPhoto = slot in form.attachments,
            actions = actions,
            onDismiss = { sheetSlot = null }
        )
    }
}
