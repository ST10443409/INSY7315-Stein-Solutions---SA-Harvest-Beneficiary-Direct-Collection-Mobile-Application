package com.example.client.sync

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.ConnectivityManager
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.client.BuildConfig
import com.example.client.data.AppDatabase
import com.example.client.data.attachments.FileAttachmentStorage
import com.example.client.data.local.entity.AttachmentKind
import com.example.client.data.local.entity.CboCollectionEntity
import com.example.client.data.local.entity.CollectionAttachmentEntity
import com.example.client.data.local.entity.ProductLineEntity
import com.example.client.data.local.entity.SyncStatus
import com.example.client.data.repository.CboCollectionRepositoryImpl
import com.example.client.network.AttachmentApiService
import com.example.client.network.AuthApiService
import com.example.client.network.HttpClients
import com.example.client.network.LoginRequest
import com.example.client.network.SyncApiService
import com.google.gson.JsonParser
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Signatures and photos on a real device or emulator, against the real backend and its blob storage: the files are made and
 * stored by the real [FileAttachmentStorage], saved through the real repository into a real Room database, and sent by the
 * real processors over the production OkHttp set-up. Then an Admin reads them back from the server, and the bytes must be the
 * ones that were captured.
 *
 * Opt-in, like [ConnectivityScenarioTest]: it needs the backend (docker compose up, which includes the Azurite storage
 * emulator) and test accounts, and is skipped unless the password is passed.
 *
 * Instrumentation arguments: e2ePassword (required), e2eUser (default cbo_test_user), e2eAdmin (default admin_test_user),
 * e2eBaseUrl (default the app's API_BASE_URL).
 */
@RunWith(AndroidJUnit4::class)
class AttachmentUploadScenarioTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val args = InstrumentationRegistry.getArguments()
    private val baseUrl = args.getString("e2eBaseUrl") ?: BuildConfig.API_BASE_URL

    private lateinit var db: AppDatabase
    private lateinit var collectorToken: String
    private lateinit var adminToken: String
    private lateinit var storage: FileAttachmentStorage
    private val created = mutableListOf<File>()

    @Before
    fun setUp() = runBlocking<Unit> {
        val password = args.getString("e2ePassword")
        assumeTrue("Needs the backend and test accounts: pass -e e2ePassword (see the class comment)", password != null)
        setAirplaneMode(false)
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        storage = FileAttachmentStorage(context)
        collectorToken = login(args.getString("e2eUser") ?: "cbo_test_user", password!!)
        adminToken = login(args.getString("e2eAdmin") ?: "admin_test_user", password)
    }

    @After
    fun tearDown() {
        if (::db.isInitialized) {
            setAirplaneMode(false)
            db.close()
        }
        created.forEach { it.delete() }
    }

    // ── scenarios ──────────────────────────────────────────────────────────────────

    @Test
    fun aCapturedSignatureAndPhoto_reachBlobStorage_andAnAdminReadsBackTheSameBytes() = runBlocking<Unit> {
        val collectionId = saveCollectionWithFiles()

        assertEquals(SyncRunResult.DONE, collectionProcessor().syncPending("tester"))
        assertEquals(SyncRunResult.DONE, uploader().uploadPending("tester"))

        val local = db.cboCollectionDao().getAttachmentsForCollections(listOf(collectionId))
        assertEquals(3, local.size)
        assertTrue("every file is SYNCED", local.all { it.syncStatus == SyncStatus.SYNCED && it.retryCount == 0 && it.syncErrorCode == null })
        assertServerHasExactly(collectionId, local)
    }

    @Test
    fun filesCapturedOffline_waitForTheRecord_thenGoUpOnceTheNetworkIsBack() = runBlocking<Unit> {
        setAirplaneMode(true)
        val collectionId = saveCollectionWithFiles()

        assertEquals(SyncRunResult.RETRY_LATER, collectionProcessor().syncPending("tester"))
        assertEquals("a file is never offered before its record is on the server", SyncRunResult.DONE, uploader().uploadPending("tester"))
        assertEquals(setOf(SyncStatus.PENDING), db.cboCollectionDao().getAttachmentsForCollections(listOf(collectionId)).map { it.syncStatus }.toSet())

        setAirplaneMode(false)
        assertEquals(SyncRunResult.DONE, collectionProcessor().syncPending("tester"))
        assertEquals(SyncRunResult.DONE, uploader().uploadPending("tester"))

        val local = db.cboCollectionDao().getAttachmentsForCollections(listOf(collectionId))
        assertTrue(local.all { it.syncStatus == SyncStatus.SYNCED })
        assertServerHasExactly(collectionId, local)
    }

    @Test
    fun sendingTheSameFilesAgain_afterALostAnswer_changesNothingOnTheServer() = runBlocking<Unit> {
        val collectionId = saveCollectionWithFiles()
        collectionProcessor().syncPending("tester")
        uploader().uploadPending("tester")
        // The phone never saw the answers: it believes the files are still waiting, and sends them all again.
        db.cboCollectionDao().insertAttachments(db.cboCollectionDao().getAttachmentsForCollections(listOf(collectionId)).map { it.copy(syncStatus = SyncStatus.PENDING) })

        assertEquals(SyncRunResult.DONE, uploader().uploadPending("tester"))

        val local = db.cboCollectionDao().getAttachmentsForCollections(listOf(collectionId))
        assertTrue("the retry is a success, not a conflict", local.all { it.syncStatus == SyncStatus.SYNCED && it.syncErrorCode == null })
        assertServerHasExactly(collectionId, local) // still exactly the three files, not six
    }

    @Test
    fun aFileTheServerRefuses_isRejectedWithItsReason_andTheOthersStillGoUp() = runBlocking<Unit> {
        val collectionId = saveCollectionWithFiles()
        // Replace one file's bytes with something that is not an image, as a corrupted or tampered file would be.
        val victim = db.cboCollectionDao().getAttachmentsForCollections(listOf(collectionId)).first { it.kind == AttachmentKind.PHOTO }
        File(victim.filePath).writeBytes("not an image at all".toByteArray())
        collectionProcessor().syncPending("tester")

        assertEquals(SyncRunResult.DONE, uploader().uploadPending("tester"))

        val byId = db.cboCollectionDao().getAttachmentsForCollections(listOf(collectionId)).associateBy { it.id }
        assertEquals(SyncStatus.FAILED, byId.getValue(victim.id).syncStatus)
        assertEquals("UNSUPPORTED_MEDIA_TYPE", byId.getValue(victim.id).syncErrorCode)
        assertEquals(SyncPolicy.MAX_RETRIES, byId.getValue(victim.id).retryCount)
        assertEquals(2, byId.values.count { it.syncStatus == SyncStatus.SYNCED })
    }

    // ── helpers ────────────────────────────────────────────────────────────────────

    /** A collection with a donor signature, a CBO signature and one photo, captured and stored the way Form 1 does. */
    private suspend fun saveCollectionWithFiles(): String {
        val id = UUID.randomUUID().toString()
        val donorSignature = storage.saveSignature(png(drawSignature(seed = 1)))
        val cboSignature = storage.saveSignature(png(drawSignature(seed = 2)))
        val photo = checkNotNull(storage.savePhoto(Uri.fromFile(sourcePhoto()).toString())) { "the photo could not be stored" }
        listOf(donorSignature.path, cboSignature.path, photo.path).forEach { created += File(it) }

        fun attachment(kind: AttachmentKind, file: com.example.client.data.attachments.StoredFile, slot: Int = 0) = CollectionAttachmentEntity(
            collectionId = id, kind = kind, slot = slot, filePath = file.path, mimeType = file.mimeType, sizeBytes = file.sizeBytes
        )

        val collection = CboCollectionEntity(
            id = id, cboId = "set-by-the-server", arrivalTime = "09:00", departureTime = "09:40", donorName = "Spar Gugulethu",
            donorSigned = true, cboSigned = true, deliveryNote = "E2E-FILES-$id", noteAttached = false, collectNotes = "",
            shots = listOf(true, false), latitude = null, longitude = null
        )
        val line = ProductLineEntity(collectionId = id, category = "Fresh produce", kg = "12.5", notes = null)
        CboCollectionRepositoryImpl(db.cboCollectionDao()).save(
            collection, listOf(line),
            listOf(
                attachment(AttachmentKind.DONOR_SIGNATURE, donorSignature),
                attachment(AttachmentKind.CBO_SIGNATURE, cboSignature),
                attachment(AttachmentKind.PHOTO, photo, slot = 0)
            )
        )
        return id
    }

    private fun drawSignature(seed: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(600, 240, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint().apply { color = Color.BLACK; strokeWidth = 5f; isAntiAlias = true }
        for (i in 0 until 12) canvas.drawLine(20f + i * 40f, 40f + (seed * 17 + i * 23) % 150, 60f + i * 40f, 190f - (seed * 11 + i * 31) % 120, paint)
        return bitmap
    }

    /** A photo-like JPEG in the cache, large enough that the real storage has to scale it. */
    private fun sourcePhoto(): File {
        val bitmap = Bitmap.createBitmap(2400, 1800, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val random = java.util.Random(42)
        val paint = Paint()
        for (i in 0 until 400) {
            paint.color = Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256))
            canvas.drawCircle(random.nextInt(2400).toFloat(), random.nextInt(1800).toFloat(), 20f + random.nextInt(120), paint)
        }
        val file = File(context.cacheDir, "e2e-source-${UUID.randomUUID()}.jpg").also { created += it }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        return file
    }

    private fun png(bitmap: Bitmap): ByteArray = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()

    /** Asks the server, as an Admin, what it holds for this collection, and reads every file back byte for byte. */
    private fun assertServerHasExactly(collectionId: String, local: List<CollectionAttachmentEntity>) {
        val listing = adminGet("/api/admin/collections/$collectionId/attachments")
        assertEquals("listing", 200, listing.first)
        val items = JsonParser.parseString(String(listing.second)).asJsonObject["data"].asJsonObject["attachments"].asJsonArray
        assertEquals("the server holds exactly the files that were captured", local.size, items.size())

        for (file in local) {
            val item = items.map { it.asJsonObject }.single { it.get("id").asString == file.id }
            assertEquals(file.kind.name, item.get("kind").asString)
            assertEquals(file.slot, item.get("slot").asInt)
            assertEquals(file.mimeType, item.get("contentType").asString)
            val sent = File(file.filePath).readBytes()
            assertEquals(sent.size.toLong(), item.get("sizeBytes").asLong)

            val read = adminGet("/api/admin/attachments/${file.id}")
            assertEquals("reading ${file.kind}", 200, read.first)
            assertArrayEquals("the bytes that came back from blob storage are the bytes that were captured (${file.kind})", sent, read.second)
        }
    }

    private fun adminGet(path: String): Pair<Int, ByteArray> {
        val request = Request.Builder().url(baseUrl.trimEnd('/') + path).header("Authorization", "Bearer $adminToken").build()
        return OkHttpClient().newCall(request).execute().use { it.code to (it.body?.bytes() ?: ByteArray(0)) }
    }

    private fun login(user: String, password: String): String = runBlocking {
        val response = retrofit(HttpClients.builder().build()).create(AuthApiService::class.java).login(LoginRequest(user, password))
        requireNotNull(response.body()?.token) { "login failed for $user: HTTP ${response.code()}" }
    }

    private inner class BearerToken : Interceptor {
        override fun intercept(chain: Interceptor.Chain) =
            chain.proceed(chain.request().newBuilder().header("Authorization", "Bearer $collectorToken").build())
    }

    private fun retrofit(client: OkHttpClient): Retrofit =
        Retrofit.Builder().baseUrl(baseUrl).addConverterFactory(GsonConverterFactory.create()).client(client).build()

    private fun authed(): Retrofit = retrofit(HttpClients.builder().addInterceptor(BearerToken()).build())

    private fun collectionProcessor() = CboSyncProcessor(db.cboCollectionDao(), db.productLineDao(), authed().create(SyncApiService::class.java))

    private fun uploader() = AttachmentUploadProcessor(db.cboCollectionDao(), authed().create(AttachmentApiService::class.java))

    /** Toggles airplane mode the way a user would (radios off), then waits until the network has really gone or come back. */
    private fun setAirplaneMode(on: Boolean) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val output = automation.executeShellCommand("cmd connectivity airplane-mode ${if (on) "enable" else "disable"}")
        ParcelFileDescriptor.AutoCloseInputStream(output).use { it.readBytes() }

        val deadline = SystemClock.elapsedRealtime() + TimeUnit.SECONDS.toMillis(90)
        while (SystemClock.elapsedRealtime() < deadline) {
            if (if (on) !hasNetwork() else backendAnswers()) return
            SystemClock.sleep(250)
        }
        throw AssertionError("the network did not ${if (on) "go away" else "come back"} within 90 s")
    }

    private fun hasNetwork(): Boolean =
        (context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).activeNetwork != null

    private fun backendAnswers(): Boolean = try {
        OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build()
            .newCall(Request.Builder().url(baseUrl.trimEnd('/') + "/api/health").build()).execute()
            .use { it.isSuccessful }
    } catch (e: IOException) {
        false
    }
}
