package com.example.client.sync

import com.example.client.data.local.entity.AttachmentKind
import com.example.client.data.local.entity.CboCollectionEntity
import com.example.client.data.local.entity.CollectionAttachmentEntity
import com.example.client.data.local.entity.SyncStatus
import com.example.client.network.ApiEnvelope
import com.example.client.network.AttachmentApiService
import com.example.client.network.AttachmentErrorCodes
import com.example.client.network.AttachmentUploadResponse
import com.example.client.testing.InMemoryCboCollectionDao
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import java.io.File
import java.io.IOException

/**
 * What happens to each signature and photo after an upload attempt, and what is (and is not) sent. Free of Android and of the
 * network: the files are real temporary files, the server is a fake that records what it was given.
 */
class AttachmentUploadProcessorTest {

    private class Upload(
        val collectionId: String, val attachmentId: String, val kind: String, val slot: Int, val bytes: ByteArray, val contentType: String?
    )

    private class FakeApi(var handler: (Upload) -> Response<ApiEnvelope<AttachmentUploadResponse>>) : AttachmentApiService {
        val uploads = mutableListOf<Upload>()

        override suspend fun uploadAttachment(
            collectionId: String, attachmentId: String, kind: String, slot: Int, file: RequestBody
        ): Response<ApiEnvelope<AttachmentUploadResponse>> {
            val bytes = Buffer().also { file.writeTo(it) }.readByteArray()
            val upload = Upload(collectionId, attachmentId, kind, slot, bytes, file.contentType()?.toString())
            uploads += upload
            return handler(upload)
        }
    }

    private lateinit var dir: File
    private lateinit var dao: InMemoryCboCollectionDao
    private lateinit var api: FakeApi
    private lateinit var processor: AttachmentUploadProcessor

    @Before
    fun setUp() {
        dir = File(System.getProperty("java.io.tmpdir"), "att-test-" + System.nanoTime()).apply { mkdirs() }
        dao = InMemoryCboCollectionDao()
        api = FakeApi { stored(it) }
        processor = AttachmentUploadProcessor(dao, api)
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun stored(upload: Upload, alreadyReceived: Boolean = false) =
        Response.success(ApiEnvelope(true, AttachmentUploadResponse(upload.attachmentId, alreadyReceived, upload.bytes.size.toLong())))

    private fun refused(code: Int, errorCode: String?): Response<ApiEnvelope<AttachmentUploadResponse>> {
        val body = if (errorCode == null) "<html>bad gateway</html>" else """{"success":false,"data":null,"error":{"code":"$errorCode","message":"no"}}"""
        return Response.error(code, body.toResponseBody("application/json".toMediaType()))
    }

    private fun collection(id: String, status: SyncStatus = SyncStatus.SYNCED, error: String? = null, author: String? = "tester") =
        CboCollectionEntity(
            id = id, cboId = "cbo", arrivalTime = "09:00", departureTime = null, donorName = "D", donorSigned = true, cboSigned = true,
            deliveryNote = "", noteAttached = false, collectNotes = "", shots = listOf(true), latitude = null, longitude = null,
            syncStatus = status, syncErrorCode = error, authorUsername = author
        )

    private fun file(name: String, bytes: ByteArray = byteArrayOf(1, 2, 3, 4)) = File(dir, name).apply { writeBytes(bytes) }

    private fun attachment(
        id: String, collectionId: String, kind: AttachmentKind = AttachmentKind.PHOTO, slot: Int = 0,
        mime: String = "image/jpeg", file: File = file("$id.bin"), status: SyncStatus = SyncStatus.PENDING, retryCount: Int = 0
    ) = CollectionAttachmentEntity(
        id = id, collectionId = collectionId, kind = kind, slot = slot, filePath = file.absolutePath, mimeType = mime,
        sizeBytes = file.length(), syncStatus = status, retryCount = retryCount
    )

    private fun seed(c: CboCollectionEntity, vararg a: CollectionAttachmentEntity) {
        dao.rows.value = dao.rows.value + c
        dao.attachments.value = dao.attachments.value + a
    }

    // ── what is sent ───────────────────────────────────────────────────────────────

    @Test
    fun aWaitingFile_isUploadedWithItsIdKindSlotTypeAndExactBytes_andBecomesSynced() = runTest {
        val bytes = byteArrayOf(9, 8, 7, 6, 5)
        seed(collection("c1"), attachment("a1", "c1", AttachmentKind.PHOTO, slot = 2, file = file("p.jpg", bytes)))

        assertEquals(SyncRunResult.DONE, processor.uploadPending("tester"))

        val sent = api.uploads.single()
        assertEquals(listOf("c1", "a1", "PHOTO", 2), listOf(sent.collectionId, sent.attachmentId, sent.kind, sent.slot))
        assertEquals("image/jpeg", sent.contentType)
        assertEquals(bytes.toList(), sent.bytes.toList())
        assertEquals(SyncStatus.SYNCED, dao.attachment("a1").syncStatus)
    }

    @Test
    fun everyKindIsSentUnderItsWireName() = runTest {
        seed(
            collection("c1"),
            attachment("s1", "c1", AttachmentKind.DONOR_SIGNATURE, mime = "image/png"),
            attachment("s2", "c1", AttachmentKind.CBO_SIGNATURE, mime = "image/png"),
            attachment("n1", "c1", AttachmentKind.DELIVERY_NOTE)
        )

        processor.uploadPending("tester")

        assertEquals(setOf("DONOR_SIGNATURE", "CBO_SIGNATURE", "DELIVERY_NOTE"), api.uploads.map { it.kind }.toSet())
    }

    @Test
    fun theServerSayingItAlreadyHasTheFile_isSuccess() = runTest {
        seed(collection("c1"), attachment("a1", "c1"))
        api.handler = { stored(it, alreadyReceived = true) }

        processor.uploadPending("tester")

        assertEquals(SyncStatus.SYNCED, dao.attachment("a1").syncStatus)
    }

    @Test
    fun aFileIsOnlySentOnceItsRecordIsOnTheServer() = runTest {
        seed(collection("waiting", status = SyncStatus.PENDING), attachment("a1", "waiting"))
        seed(collection("rejected", status = SyncStatus.FAILED, error = "VALIDATION_FAILED"), attachment("a2", "rejected"))

        processor.uploadPending("tester")

        assertTrue("nothing may be sent before its record is safe", api.uploads.isEmpty())
        assertEquals(SyncStatus.PENDING, dao.attachment("a1").syncStatus)
    }

    @Test
    fun aDuplicateRecordsFilesAreStillSent_theServerKeptThatRecordForReview() = runTest {
        seed(collection("dup", status = SyncStatus.FAILED, error = "DUPLICATE_DETECTED"), attachment("a1", "dup"))

        processor.uploadPending("tester")

        assertEquals(1, api.uploads.size)
    }

    @Test
    fun onlyTheSignedInUsersFilesAreSent_neverSomeoneElses() = runTest {
        seed(collection("mine", author = "tester"), attachment("a1", "mine"))
        seed(collection("theirs", author = "someone.else"), attachment("a2", "theirs"))

        processor.uploadPending("tester")

        assertEquals(listOf("a1"), api.uploads.map { it.attachmentId })
        assertEquals(SyncStatus.PENDING, dao.attachment("a2").syncStatus)
    }

    @Test
    fun aFileThatIsAlreadyUploaded_isNotSentAgain() = runTest {
        seed(collection("c1"), attachment("done", "c1", status = SyncStatus.SYNCED))

        processor.uploadPending("tester")

        assertTrue(api.uploads.isEmpty())
    }

    // ── refused for good ───────────────────────────────────────────────────────────

    @Test
    fun aFileTheServerRefusesForGood_isRejected_withTheServersCode_andNeverSentAgain() = runTest {
        for ((code, errorCode) in listOf(400 to "VALIDATION_FAILED", 404 to "NOT_FOUND", 409 to "CONFLICT", 413 to "PAYLOAD_TOO_LARGE", 415 to "UNSUPPORTED_MEDIA_TYPE")) {
            dao.rows.value = emptyList(); dao.attachments.value = emptyList(); api.uploads.clear()
            seed(collection("c1"), attachment("a1", "c1"))
            api.handler = { refused(code, errorCode) }

            processor.uploadPending("tester")
            processor.uploadPending("tester") // a second run must not try it again

            val row = dao.attachment("a1")
            assertEquals("$code", SyncStatus.FAILED, row.syncStatus)
            assertEquals(errorCode, row.syncErrorCode)
            assertEquals(SyncPolicy.MAX_RETRIES, row.retryCount)
            assertEquals("$code was sent again", 1, api.uploads.size)
        }
    }

    @Test
    fun oneRefusedFile_doesNotStopTheOthers() = runTest {
        seed(collection("c1"), attachment("bad", "c1", slot = 0), attachment("good", "c1", slot = 1))
        api.handler = { if (it.attachmentId == "bad") refused(415, "UNSUPPORTED_MEDIA_TYPE") else stored(it) }

        assertEquals(SyncRunResult.DONE, processor.uploadPending("tester"))

        assertEquals(SyncStatus.FAILED, dao.attachment("bad").syncStatus)
        assertEquals(SyncStatus.SYNCED, dao.attachment("good").syncStatus)
    }

    @Test
    fun aFileThatIsGoneFromThePhone_isRejected_withoutCallingTheServer() = runTest {
        val gone = file("gone.jpg").also { it.delete() }
        seed(collection("c1"), attachment("a1", "c1", file = gone))

        assertEquals(SyncRunResult.DONE, processor.uploadPending("tester"))

        assertTrue(api.uploads.isEmpty())
        assertEquals(AttachmentErrorCodes.FILE_MISSING, dao.attachment("a1").syncErrorCode)
        assertEquals(SyncStatus.FAILED, dao.attachment("a1").syncStatus)
    }

    @Test
    fun anEmptyFile_isTreatedAsGone() = runTest {
        seed(collection("c1"), attachment("a1", "c1", file = file("empty.jpg", ByteArray(0))))

        processor.uploadPending("tester")

        assertTrue(api.uploads.isEmpty())
        assertEquals(AttachmentErrorCodes.FILE_MISSING, dao.attachment("a1").syncErrorCode)
    }

    // ── a bad moment must never throw a photo away ─────────────────────────────────

    @Test
    fun noConnection_stopsTheRun_andLeavesEveryFileWaiting() = runTest {
        seed(collection("c1"), attachment("a1", "c1", slot = 0), attachment("a2", "c1", slot = 1))
        api.handler = { throw IOException("no route to host") }

        assertEquals(SyncRunResult.RETRY_LATER, processor.uploadPending("tester"))

        assertEquals(1, api.uploads.size) // it did not hammer the dead link with every file
        assertEquals(setOf(SyncStatus.PENDING), dao.attachments.value.map { it.syncStatus }.toSet())
        assertEquals(0, dao.attachment("a1").retryCount)
    }

    @Test
    fun aTransientAnswer_stopsTheRun_andMarksNothing() = runTest {
        for (code in listOf(401, 403, 408, 429, 500, 502, 503, 504)) {
            dao.rows.value = emptyList(); dao.attachments.value = emptyList(); api.uploads.clear()
            seed(collection("c1"), attachment("a1", "c1"))
            api.handler = { refused(code, "SERVICE_UNAVAILABLE") }

            assertEquals("$code", SyncRunResult.RETRY_LATER, processor.uploadPending("tester"))

            assertEquals("$code", SyncStatus.PENDING, dao.attachment("a1").syncStatus)
            assertEquals("$code", 0, dao.attachment("a1").retryCount)
        }
    }

    @Test
    fun anAnswerThatIsNotTheApis_isNotHeldAgainstTheFile() = runTest {
        // A 404 from a proxy or a stale deployment has no error envelope. It says nothing about THIS file.
        seed(collection("c1"), attachment("a1", "c1"))
        api.handler = { refused(404, null) }

        assertEquals(SyncRunResult.RETRY_LATER, processor.uploadPending("tester"))

        assertEquals(SyncStatus.PENDING, dao.attachment("a1").syncStatus)
        assertEquals(0, dao.attachment("a1").retryCount)
    }

    @Test
    fun aFailedFileWithRetriesLeft_isTriedAgain_andOneWithNoneLeftIsNot() = runTest {
        seed(
            collection("c1"),
            attachment("retry", "c1", slot = 0, status = SyncStatus.FAILED, retryCount = 2),
            attachment("spent", "c1", slot = 1, status = SyncStatus.FAILED, retryCount = SyncPolicy.MAX_RETRIES)
        )

        processor.uploadPending("tester")

        assertEquals(listOf("retry"), api.uploads.map { it.attachmentId })
        assertEquals(SyncStatus.SYNCED, dao.attachment("retry").syncStatus)
    }
}
