package com.example.client.sync

import com.example.client.data.local.dao.CboCollectionDao
import com.example.client.network.AttachmentApiService
import com.example.client.network.AttachmentErrorCodes
import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import retrofit2.Response
import java.io.File
import java.io.IOException
import javax.inject.Inject

/**
 * Uploads the signatures and photos of Form 1 to the backend, one file per request, after the collection they belong to has
 * been received (the database query only returns files whose collection the server already has, so a file never arrives before
 * its record). What each file's status becomes, and why:
 *
 * - the server has it (200, including "already received", which is what a retry after a lost answer gets): SYNCED.
 * - the server refuses it for good (400 invalid, 404 not this user's collection, 409 the slot already holds a different file,
 *   413 too large, 415 not a JPEG/PNG): FAILED with every retry used up, with the server's code kept so the UI can say why. The
 *   same bytes will never be accepted, so they are not sent again.
 * - the file is gone from the phone: the same, with [AttachmentErrorCodes.FILE_MISSING].
 * - the server cannot be reached, or answers with something that is not about this file (401/403 session, 429, 5xx including
 *   503 "storage is down"), or with a body that is not the API's own error envelope (a proxy, a stale deployment): nothing is
 *   marked, the run stops, and the file is retried later. A bad moment must never throw away a photo.
 *
 * Only [author]'s files are sent (#70): the server stores a file only against the collection's submitter.
 */
class AttachmentUploadProcessor @Inject constructor(
    private val collectionDao: CboCollectionDao,
    private val api: AttachmentApiService
) {
    suspend fun uploadPending(author: String, now: () -> Long = System::currentTimeMillis): SyncRunResult {
        for (attachment in collectionDao.getUploadableAttachments(SyncPolicy.MAX_RETRIES, author)) {
            val file = File(attachment.filePath)
            if (!file.isFile || file.length() == 0L) {
                collectionDao.markAttachmentsRejected(
                    listOf(attachment.id), AttachmentErrorCodes.FILE_MISSING, SyncPolicy.MAX_RETRIES, now()
                )
                continue
            }

            val response = try {
                api.uploadAttachment(
                    collectionId = attachment.collectionId,
                    attachmentId = attachment.id,
                    kind = attachment.kind.name,
                    slot = attachment.slot,
                    // A type the server will not accept (it should never happen) goes as plain bytes and is refused with 415.
                    file = file.asRequestBody(attachment.mimeType.toMediaTypeOrNull())
                )
            } catch (e: IOException) {
                return SyncRunResult.RETRY_LATER // no connection, or it dropped mid-upload: the same file goes again next run
            }

            if (response.isSuccessful) {
                collectionDao.markAttachmentsUploaded(listOf(attachment.id), now())
                continue
            }
            if (response.code().isTransient()) return SyncRunResult.RETRY_LATER

            val errorCode = errorCodeOf(response) ?: return SyncRunResult.RETRY_LATER
            collectionDao.markAttachmentsRejected(listOf(attachment.id), errorCode, SyncPolicy.MAX_RETRIES, now())
        }
        return SyncRunResult.DONE
    }

    // Not the file's fault: not signed in, not allowed for now, throttled, or the server (or its storage) having a bad moment.
    private fun Int.isTransient() = this == 401 || this == 403 || this == 408 || this == 429 || this >= 500

    /** The `error.code` of the API's own error envelope, or null when the body is anything else. Never throws. */
    private fun errorCodeOf(response: Response<*>): String? = try {
        JsonParser.parseString(response.errorBody()?.string().orEmpty())
            .asJsonObject["error"]?.asJsonObject?.get("code")?.asString
    } catch (e: Exception) {
        null
    }
}
