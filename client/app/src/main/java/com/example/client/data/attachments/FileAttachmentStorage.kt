package com.example.client.data.attachments

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

/**
 * Stores signature and photo files under `files/attachments/` in the app's private storage. The directory is also the
 * only one the camera's FileProvider shares (see res/xml/file_paths.xml), so nothing else is ever exposed.
 */
@Singleton
class FileAttachmentStorage @Inject constructor(
    @ApplicationContext private val context: Context
) : AttachmentStorage {

    private val dir: File get() = File(context.filesDir, DIR).apply { mkdirs() }

    override suspend fun saveSignature(png: ByteArray): StoredFile = withContext(Dispatchers.IO) {
        val file = File(dir, "sig_${UUID.randomUUID()}.png")
        file.writeBytes(png)
        StoredFile(file.absolutePath, "image/png", file.length())
    }

    override suspend fun savePhoto(sourceUri: String): StoredFile? = withContext(Dispatchers.IO) {
        val uri = Uri.parse(sourceUri)
        val target = File(dir, "photo_${UUID.randomUUID()}.jpg")
        try {
            val rotation = context.contentResolver.openInputStream(uri)?.use { exifRotation(it) } ?: 0
            val bitmap = decodeScaled { context.contentResolver.openInputStream(uri) } ?: return@withContext null
            writeJpeg(rotate(bitmap, rotation), target)
            StoredFile(target.absolutePath, "image/jpeg", target.length())
        } catch (e: IOException) {
            target.delete()
            null
        } catch (e: SecurityException) {
            target.delete()
            null
        }
    }

    override fun newCaptureTarget(): CaptureTarget {
        val file = File(dir, "capture_${UUID.randomUUID()}.jpg")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return CaptureTarget(uri.toString(), file.absolutePath)
    }

    override suspend fun finishCapture(path: String): StoredFile? = withContext(Dispatchers.IO) {
        val file = File(path)
        if (!file.exists() || file.length() == 0L) {
            file.delete()
            return@withContext null
        }
        try {
            val rotation = file.inputStream().use { exifRotation(it) }
            val bitmap = decodeScaled { file.inputStream() } ?: run {
                file.delete()
                return@withContext null
            }
            writeJpeg(rotate(bitmap, rotation), file)
            StoredFile(file.absolutePath, "image/jpeg", file.length())
        } catch (e: IOException) {
            file.delete()
            null
        }
    }

    override fun delete(path: String) {
        File(path).delete()
    }

    /** Decodes with a sample size, so a 12 MP photo never has to be held in memory at full size. */
    private fun decodeScaled(open: () -> java.io.InputStream?): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // Reading only the size returns no bitmap by design; what matters is the size it filled in.
        (open() ?: return null).use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_EDGE) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = open()?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null

        val longest = max(decoded.width, decoded.height)
        if (longest <= MAX_EDGE) return decoded
        val factor = MAX_EDGE.toFloat() / longest
        return Bitmap.createScaledBitmap(decoded, (decoded.width * factor).toInt(), (decoded.height * factor).toInt(), true)
    }

    private fun exifRotation(input: java.io.InputStream): Int = try {
        when (ExifInterface(input).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    } catch (e: IOException) {
        0
    }

    private fun rotate(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bitmap
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun writeJpeg(bitmap: Bitmap, file: File) {
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
    }

    companion object {
        const val DIR = "attachments"

        /** The longest edge of a stored photo, in pixels: plenty to read a delivery note, small enough to upload on mobile data. */
        const val MAX_EDGE = 1600
        const val JPEG_QUALITY = 85
    }
}
