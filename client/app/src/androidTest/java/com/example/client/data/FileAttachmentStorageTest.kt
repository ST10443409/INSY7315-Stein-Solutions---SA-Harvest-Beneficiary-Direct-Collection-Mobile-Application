package com.example.client.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.client.data.attachments.FileAttachmentStorage
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * The signature and photo files, on a device: what the camera and the gallery hand over is scaled down and stored in the
 * app's private folder, and the camera can actually write into the file it is given.
 */
@RunWith(AndroidJUnit4::class)
class FileAttachmentStorageTest {

    private lateinit var context: Context
    private lateinit var storage: FileAttachmentStorage
    private val scratch = mutableListOf<File>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        storage = FileAttachmentStorage(context)
    }

    @After
    fun cleanUp() {
        scratch.forEach { it.delete() }
        File(context.filesDir, FileAttachmentStorage.DIR).deleteRecursively()
    }

    private fun jpeg(width: Int, height: Int, orientation: Int? = null): File {
        val file = File(context.cacheDir, "source_${System.nanoTime()}.jpg").also { scratch += it }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.rgb(200, 120, 40)) }
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        if (orientation != null) {
            ExifInterface(file.absolutePath).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                saveAttributes()
            }
        }
        return file
    }

    private fun sizeOf(path: String) = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
        BitmapFactory.decodeFile(path, this)
    }.let { it.outWidth to it.outHeight }

    private val privateDir get() = File(context.filesDir, FileAttachmentStorage.DIR).canonicalPath

    @Test
    fun aSignature_isWrittenAsIs_intoThePrivateFolder() = runBlocking {
        val stored = storage.saveSignature(byteArrayOf(1, 2, 3, 4))

        assertEquals("image/png", stored.mimeType)
        assertEquals(4L, stored.sizeBytes)
        assertTrue(File(stored.path).canonicalPath.startsWith(privateDir))
        assertEquals(listOf<Byte>(1, 2, 3, 4), File(stored.path).readBytes().toList())
    }

    @Test
    fun aBigGalleryPhoto_isScaledDownAndStoredAsJpeg() = runBlocking {
        val source = jpeg(4000, 3000)

        val stored = storage.savePhoto(Uri.fromFile(source).toString())

        assertNotNull(stored)
        assertEquals("image/jpeg", stored!!.mimeType)
        assertTrue(File(stored.path).canonicalPath.startsWith(privateDir))
        val (w, h) = sizeOf(stored.path)
        assertEquals("the longest edge is the limit", FileAttachmentStorage.MAX_EDGE, maxOf(w, h))
        assertEquals("the shape is kept", 4.0 / 3.0, w.toDouble() / h, 0.02)
        assertTrue("and the file is a lot smaller than the original", stored.sizeBytes < source.length() || source.length() < 100_000)
    }

    @Test
    fun aSmallPhoto_isNotMadeLarger() = runBlocking {
        val stored = storage.savePhoto(Uri.fromFile(jpeg(800, 600)).toString())

        assertEquals(800 to 600, sizeOf(stored!!.path))
    }

    @Test
    fun somethingThatIsNotAnImage_isRefused_andLeavesNothingBehind() = runBlocking {
        val notAPicture = File(context.cacheDir, "notes.txt").also { scratch += it; it.writeText("not a picture") }

        val stored = storage.savePhoto(Uri.fromFile(notAPicture).toString())

        assertNull(stored)
        assertTrue(File(context.filesDir, FileAttachmentStorage.DIR).listFiles().orEmpty().isEmpty())
    }

    @Test
    fun aMissingSource_isRefused() = runBlocking {
        assertNull(storage.savePhoto(Uri.fromFile(File(context.cacheDir, "gone.jpg")).toString()))
    }

    @Test
    fun theCameraCanWriteIntoTheFileItIsGiven_andTheResultIsScaledDown() = runBlocking {
        val target = storage.newCaptureTarget()
        assertTrue(target.uri.startsWith("content://${context.packageName}.fileprovider/"))

        // What the camera app does: open the content uri it was handed and write the picture there.
        context.contentResolver.openOutputStream(Uri.parse(target.uri))!!.use { out ->
            out.write(jpeg(3200, 2400).readBytes())
        }
        val stored = storage.finishCapture(target.path)

        assertNotNull(stored)
        assertEquals(target.path, stored!!.path)
        assertEquals(FileAttachmentStorage.MAX_EDGE, sizeOf(stored.path).let { maxOf(it.first, it.second) })
    }

    @Test
    fun aCameraThatLeftNothing_givesNothing_andNoStrayFile() = runBlocking {
        val target = storage.newCaptureTarget()
        File(target.path).createNewFile() // an empty file, as when the user backs out of the camera

        assertNull(storage.finishCapture(target.path))
        assertFalse(File(target.path).exists())
    }

    @Test
    fun aPhotoTakenSideways_isTurnedUprightUsingItsOrientation() = runBlocking {
        // 600 wide by 400 tall, marked "rotate 90": it must come out 400 wide by 600 tall.
        val source = jpeg(600, 400, orientation = ExifInterface.ORIENTATION_ROTATE_90)
        val target = storage.newCaptureTarget()
        source.copyTo(File(target.path), overwrite = true)

        val stored = storage.finishCapture(target.path)

        assertEquals(400 to 600, sizeOf(stored!!.path))
    }

    @Test
    fun delete_removesTheFile_andIgnoresOneThatIsAlreadyGone() = runBlocking {
        val stored = storage.saveSignature(byteArrayOf(9))

        storage.delete(stored.path)
        storage.delete(stored.path)

        assertFalse(File(stored.path).exists())
    }
}
