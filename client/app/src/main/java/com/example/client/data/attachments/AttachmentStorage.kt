package com.example.client.data.attachments

/** A picture saved in the app's private storage. */
data class StoredFile(val path: String, val mimeType: String, val sizeBytes: Long)

/** Where the camera app should write the next photo: [uri] is handed to the camera, [path] is the same file. */
data class CaptureTarget(val uri: String, val path: String)

/**
 * Keeps the signature and photo files for Form 1 in the app's private storage. A [StoredFile]'s path is what gets
 * recorded in the database (see CollectionAttachmentEntity); the bytes never leave the device until upload is built.
 *
 * Uris travel as strings so callers (and their JVM tests) do not need Android's Uri class.
 */
interface AttachmentStorage {
    /** Saves a finger-drawn signature, already encoded as PNG. */
    suspend fun saveSignature(png: ByteArray): StoredFile

    /**
     * Copies a picture the user chose (from the photo picker, or any content uri) into private storage, scaled down and
     * compressed so a full-resolution photo does not fill the device. Null if the source cannot be read as an image.
     */
    suspend fun savePhoto(sourceUri: String): StoredFile?

    /** Reserves a file for the camera to write into. Call [finishCapture] when it reports success, else [delete]. */
    fun newCaptureTarget(): CaptureTarget

    /** Scales down and compresses a photo the camera wrote to [path]. Null if the camera left nothing usable there. */
    suspend fun finishCapture(path: String): StoredFile?

    /** Removes a stored file. Missing files are ignored. */
    fun delete(path: String)
}
