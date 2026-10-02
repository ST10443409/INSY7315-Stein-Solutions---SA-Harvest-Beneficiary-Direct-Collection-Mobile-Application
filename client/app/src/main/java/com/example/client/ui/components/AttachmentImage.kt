package com.example.client.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * Loads a stored signature or photo for display, off the main thread and scaled down to about [maxEdgePx], so a list of
 * thumbnails does not hold a full-size bitmap for each. Null while it loads, or if the file is gone or unreadable.
 */
@Composable
fun rememberAttachmentBitmap(path: String?, maxEdgePx: Int = 480): ImageBitmap? =
    produceState<ImageBitmap?>(initialValue = null, path, maxEdgePx) {
        value = if (path == null) null else withContext(Dispatchers.IO) { decodeSampled(path, maxEdgePx)?.asImageBitmap() }
    }.value

private fun decodeSampled(path: String, maxEdgePx: Int): android.graphics.Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdgePx) sample *= 2
    return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
}

/** A stored picture, or nothing while it loads. */
@Composable
fun AttachmentImage(
    path: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    contentDescription: String? = null,
    maxEdgePx: Int = 480
) {
    val bitmap = rememberAttachmentBitmap(path, maxEdgePx) ?: return
    Image(bitmap = bitmap, contentDescription = contentDescription, contentScale = contentScale, modifier = modifier)
}
