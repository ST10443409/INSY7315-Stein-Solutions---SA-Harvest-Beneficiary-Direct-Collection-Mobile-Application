package com.example.client.network

import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Uploads the signatures and photos of Form 1 (the backend keeps them in blob storage). One file per call, so a slow photo
 * never holds up another file or any record.
 *
 * `PUT .../attachments/{attachmentId}`: the attachment id is the app's own id for the file, which makes a retry after a lost
 * answer harmless (the server answers `alreadyReceived: true` and changes nothing). The body is the raw image
 * (`image/jpeg` or `image/png`); `kind` is [com.example.client.data.local.entity.AttachmentKind] by name, and `slot` says which
 * numbered photo (0 for every other kind).
 *
 * Answers: 200 stored (or already stored); 400 invalid; 404 not this user's collection; 409 that slot or id already holds a
 * different file; 413 too large; 415 not a JPEG/PNG; 401/403/429/5xx not the file's fault (503 means blob storage is down).
 */
interface AttachmentApiService {
    @PUT("/api/cbo-collection/{collectionId}/attachments/{attachmentId}")
    suspend fun uploadAttachment(
        @Path("collectionId") collectionId: String,
        @Path("attachmentId") attachmentId: String,
        @Query("kind") kind: String,
        @Query("slot") slot: Int,
        @Body file: RequestBody
    ): Response<ApiEnvelope<AttachmentUploadResponse>>
}

data class AttachmentUploadResponse(
    val attachmentId: String? = null,
    val alreadyReceived: Boolean = false,
    val sizeBytes: Long = 0
)

/** Values of the error `code` the app reacts to when an upload is refused (see the backend's ApiErrorCodes). */
object AttachmentErrorCodes {
    /** The file on the phone is gone (the app's own code, never sent by the server): nothing left to upload. */
    const val FILE_MISSING = "FILE_MISSING"
}
