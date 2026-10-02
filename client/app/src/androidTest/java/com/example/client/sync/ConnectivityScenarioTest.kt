package com.example.client.sync

import android.content.Context
import android.net.ConnectivityManager
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.client.BuildConfig
import com.example.client.data.AppDatabase
import com.example.client.data.local.entity.CboCollectionEntity
import com.example.client.data.local.entity.ProductLineEntity
import com.example.client.data.local.entity.SyncStatus
import com.example.client.data.repository.CboCollectionRepositoryImpl
import com.example.client.network.AuthApiService
import com.example.client.network.CboSyncRequest
import com.example.client.network.GzipRequestInterceptor
import com.example.client.network.HttpClients
import com.example.client.network.LoginRequest
import com.example.client.network.SyncApiService
import com.example.client.network.toSyncDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.Buffer
import okio.BufferedSink
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * #55 on a real device or emulator, against the real backend: airplane mode, a connection lost mid-sync, and throttled
 * 2G/3G links (the emulator's network profiles, set from the host). Each scenario saves collections through the real
 * repository into a real Room database and syncs them with the real processor and the production OkHttp set-up.
 *
 * Opt-in, because it needs the backend (docker compose up) and a test account: it is skipped unless the password is
 * passed. docs/performance/run-connectivity-scenarios.sh runs it once per network profile; results and how to read them
 * are in docs/performance/low-connectivity.md. Timings are written to logcat under the tag [TAG].
 *
 * Instrumentation arguments: e2ePassword (required), e2eUser (default cbo_test_user), e2eBaseUrl (default the app's
 * API_BASE_URL), e2eProfile (a label for the log lines, e.g. "gsm").
 */
@RunWith(AndroidJUnit4::class)
class ConnectivityScenarioTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val args = InstrumentationRegistry.getArguments()
    private val baseUrl = args.getString("e2eBaseUrl") ?: BuildConfig.API_BASE_URL
    private val profile = args.getString("e2eProfile") ?: "unspecified"

    private lateinit var db: AppDatabase
    private lateinit var token: String

    @Before
    fun setUp() = runBlocking<Unit> {
        val password = args.getString("e2ePassword")
        assumeTrue("Needs the backend and a test account: pass -e e2ePassword (see the class comment)", password != null)
        setAirplaneMode(false)
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()

        val started = SystemClock.elapsedRealtime()
        val login = retrofit(HttpClients.builder().build()).create(AuthApiService::class.java)
            .login(LoginRequest(args.getString("e2eUser") ?: "cbo_test_user", password!!))
        token = requireNotNull(login.body()?.token) { "login failed: HTTP ${login.code()}" }
        log("login", "took=${SystemClock.elapsedRealtime() - started}ms")
    }

    @After
    fun tearDown() {
        if (::db.isInitialized) {
            setAirplaneMode(false)
            db.close()
        }
    }

    // ── scenarios ──────────────────────────────────────────────────────────────────

    @Test
    fun airplaneMode_recordsSavedOffline_arePendingAndAllSyncAfterReconnecting() = runBlocking<Unit> {
        setAirplaneMode(true)

        val saveMs = timed { savePending(120) }
        log("airplane.save", "records=120 took=${saveMs}ms (${saveMs / 120.0}ms each)")

        val processor = processor(productionClient())
        var outcome: SyncRunResult? = null
        val offlineMs = timed { outcome = processor.syncPending("tester") }
        log("airplane.syncWhileOffline", "outcome=$outcome took=${offlineMs}ms")
        assertEquals(SyncRunResult.RETRY_LATER, outcome)
        assertNothingLostOrFailed(pending = 120, synced = 0)

        setAirplaneMode(false)
        val onlineMs = timed { outcome = processor.syncPending("tester") }
        log("airplane.syncAfterReconnect", "outcome=$outcome took=${onlineMs}ms")
        assertEquals(SyncRunResult.DONE, outcome)
        assertNothingLostOrFailed(pending = 0, synced = 120)
        assertServerHasEveryRecord()
    }

    @Test
    fun connectionLostMidUpload_syncResumesAndCompletes() = runBlocking<Unit> {
        savePending(150) // three batches of 50
        val requests = AtomicInteger()
        // Airplane mode goes on halfway through uploading the second batch.
        val client = productionClient().newBuilder().addNetworkInterceptor { chain ->
            val request = chain.request()
            val body = request.body
            if (requests.incrementAndGet() != 2 || body == null) return@addNetworkInterceptor chain.proceed(request)
            val cutOff = object : RequestBody() {
                override fun contentType() = body.contentType()
                override fun contentLength() = body.contentLength()
                override fun writeTo(sink: BufferedSink) {
                    val bytes = Buffer().also { body.writeTo(it) }
                    sink.write(bytes, bytes.size / 2)
                    sink.flush()
                    setAirplaneMode(true)
                    sink.write(bytes, bytes.size)
                    sink.flush()
                }
            }
            chain.proceed(request.newBuilder().method(request.method, cutOff).build())
        }.build()

        val outcome = processor(client).syncPending("tester")
        log("midUpload.firstRun", "outcome=$outcome requests=${requests.get()} ${countByStatus()}")
        assertEquals(SyncRunResult.RETRY_LATER, outcome)
        assertNothingLostOrFailed(pending = 100, synced = 50)

        setAirplaneMode(false)
        val resumed = processor(productionClient()).syncPending("tester")
        log("midUpload.resumed", "outcome=$resumed ${countByStatus()}")
        assertEquals(SyncRunResult.DONE, resumed)
        assertNothingLostOrFailed(pending = 0, synced = 150)
        assertServerHasEveryRecord()
    }

    @Test
    fun connectionLostBeforeTheAnswerArrives_resumesWithoutDuplicates() = runBlocking<Unit> {
        savePending(150)
        val requests = AtomicInteger()
        // The server receives and stores the second batch, then the network goes before its answer reaches the phone:
        // the phone cannot know the batch arrived, so it must resend it, and the server must not store it twice.
        val client = productionClient().newBuilder().addNetworkInterceptor(Interceptor { chain ->
            val response = chain.proceed(chain.request())
            if (requests.incrementAndGet() == 2) {
                response.close()
                setAirplaneMode(true)
                throw IOException("network lost before the answer arrived")
            }
            response
        }).build()

        assertEquals(SyncRunResult.RETRY_LATER, processor(client).syncPending("tester"))
        assertNothingLostOrFailed(pending = 100, synced = 50)

        setAirplaneMode(false)
        assertEquals(SyncRunResult.DONE, processor(productionClient()).syncPending("tester"))
        // A duplicate would come back DUPLICATE_DETECTED and be marked FAILED; every record synced means none was.
        assertNothingLostOrFailed(pending = 0, synced = 150)
        assertServerHasEveryRecord()
        log("responseLost", "all 150 synced once ${countByStatus()}")
    }

    @Test
    fun throttledNetwork_syncCompletes_andNeverBlocksTheMainThread() = runBlocking<Unit> {
        // For comparison only: the pre-#55 set-up (OkHttp defaults: 10 s timeouts, no request compression), and each of
        // the two #55 changes on its own, to show what each contributes.
        savePending(100)
        val before = runMeasured("throttled.before(defaults,noGzip)", OkHttpClient.Builder())
        db.clearAllTables()
        savePending(100)
        runMeasured("throttled.timeoutsOnly", OkHttpClient.Builder()
            .connectTimeout(HttpClients.CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(HttpClients.READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(HttpClients.WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        db.clearAllTables()
        savePending(100)
        runMeasured("throttled.gzipOnly", OkHttpClient.Builder().addInterceptor(GzipRequestInterceptor()))
        db.clearAllTables()

        savePending(100)
        val after = runMeasured("throttled.after(HttpClients)", productionClient().newBuilder())
        assertEquals(SyncRunResult.DONE, after.outcome)
        assertNothingLostOrFailed(pending = 0, synced = 100)
        assertTrue("main thread stalled ${after.maxMainThreadStallMs}ms", after.maxMainThreadStallMs < 250)
        log("throttled.summary", "before=${before.outcome}/${before.ms}ms/${before.bytesUp}B after=${after.outcome}/${after.ms}ms/${after.bytesUp}B")
    }

    // ── measuring ──────────────────────────────────────────────────────────────────

    private class Run(val outcome: SyncRunResult, val ms: Long, val bytesUp: Long, val maxMainThreadStallMs: Long)

    /** One sync run off the main thread (as WorkManager runs it), with the bytes it uploaded and how late the main thread got. */
    private suspend fun runMeasured(label: String, builder: OkHttpClient.Builder): Run {
        val bytesUp = AtomicLong()
        val client = builder.addInterceptor(BearerToken()).eventListener(object : EventListener() {
            override fun requestBodyEnd(call: Call, byteCount: Long) {
                bytesUp.addAndGet(byteCount)
            }
        }).build()
        val watchdog = MainThreadWatchdog().apply { start() }
        val started = SystemClock.elapsedRealtime()
        val outcome = withContext(Dispatchers.IO) { processor(client, withToken = false).syncPending("tester") }
        val ms = SystemClock.elapsedRealtime() - started
        val stall = watchdog.stop()
        log(label, "outcome=$outcome took=${ms}ms bytesUp=$bytesUp maxMainThreadStall=${stall}ms ${countByStatus()}")
        return Run(outcome, ms, bytesUp.get(), stall)
    }

    /** Posts to the main thread every 16 ms and records the worst delay, the way a frame would be delayed. */
    private class MainThreadWatchdog {
        private val handler = Handler(Looper.getMainLooper())
        @Volatile private var running = true
        @Volatile private var worst = 0L
        private var expected = 0L

        private val tick = object : Runnable {
            override fun run() {
                val now = SystemClock.uptimeMillis()
                worst = maxOf(worst, now - expected)
                expected = now + 16
                if (running) handler.postDelayed(this, 16)
            }
        }

        fun start() {
            expected = SystemClock.uptimeMillis()
            handler.post(tick)
        }

        fun stop(): Long {
            running = false
            return worst
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────────────

    private inner class BearerToken : Interceptor {
        override fun intercept(chain: Interceptor.Chain) =
            chain.proceed(chain.request().newBuilder().header("Authorization", "Bearer $token").build())
    }

    private fun productionClient(): OkHttpClient = HttpClients.builder().build()

    private fun retrofit(client: OkHttpClient): Retrofit =
        Retrofit.Builder().baseUrl(baseUrl).addConverterFactory(GsonConverterFactory.create()).client(client).build()

    private fun processor(client: OkHttpClient, withToken: Boolean = true): CboSyncProcessor {
        val authed = if (withToken) client.newBuilder().addInterceptor(BearerToken()).build() else client
        return CboSyncProcessor(db.cboCollectionDao(), db.productLineDao(), retrofit(authed).create(SyncApiService::class.java))
    }

    /** Saves through the real repository, as Form 1 does: each collection with three product lines, all PENDING. */
    private suspend fun savePending(count: Int) {
        val repository = CboCollectionRepositoryImpl(db.cboCollectionDao())
        repeat(count) { i ->
            val id = UUID.randomUUID().toString()
            val collection = CboCollectionEntity(
                id = id, cboId = "set-by-the-server", arrivalTime = "09:%02d".format(i % 60), departureTime = "10:%02d".format(i % 60),
                donorName = listOf("Pick n Pay Khayelitsha", "Shoprite Mitchells Plain", "Spar Gugulethu")[i % 3],
                donorSigned = true, cboSigned = true, deliveryNote = "E2E-$id", noteAttached = true,
                collectNotes = "Collected from the back entrance; manager on duty signed. Two crates returned damaged.",
                shots = listOf(true, true, false, false), latitude = -34.0386 + i / 1000.0, longitude = 18.6774 + i / 1000.0
            )
            val lines = (0..2).map { n ->
                ProductLineEntity(
                    collectionId = id, category = listOf("Fresh produce", "Bakery", "Dairy")[n],
                    kg = "${10 + n}.${i % 10}", notes = "Mostly in date"
                )
            }
            repository.save(collection, lines, emptyList())
        }
    }

    private suspend fun countByStatus() =
        db.cboCollectionDao().getAll().first().groupingBy { it.syncStatus }.eachCount()

    private suspend fun assertNothingLostOrFailed(pending: Int, synced: Int) {
        val rows = db.cboCollectionDao().getAll().first()
        assertEquals("rows on the device", pending + synced, rows.size)
        assertEquals("pending", pending, rows.count { it.syncStatus == SyncStatus.PENDING })
        assertEquals("synced", synced, rows.count { it.syncStatus == SyncStatus.SYNCED })
        assertTrue("connectivity must never use up a record's retries", rows.all { it.retryCount == 0 && it.syncErrorCode == null })
        val lines = db.productLineDao().getForCollections(rows.map { it.id })
        assertEquals("product lines on the device", rows.size * 3, lines.size)
        val statusById = rows.associate { it.id to it.syncStatus }
        assertTrue("product lines share their collection's status", lines.all { it.syncStatus == statusById[it.collectionId] })
    }

    /** Sends every record again: the server answers "already received" only for records it has stored. */
    private suspend fun assertServerHasEveryRecord() {
        val api = retrofit(productionClient().newBuilder().addInterceptor(BearerToken()).build()).create(SyncApiService::class.java)
        val rows = db.cboCollectionDao().getAll().first()
        val lines = db.productLineDao().getForCollections(rows.map { it.id }).groupBy { it.collectionId }
        for (batch in rows.chunked(SyncPolicy.BATCH_SIZE)) {
            val results = api.syncCboCollections(CboSyncRequest(batch.map { it.toSyncDto(lines[it.id].orEmpty()) })).body()!!.data!!.results
            assertEquals(batch.size, results.size)
            assertTrue("the server has every record exactly once", results.all { it.success && it.alreadyReceived })
        }
    }

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

    private inline fun timed(block: () -> Unit): Long {
        val started = SystemClock.elapsedRealtime()
        block()
        return SystemClock.elapsedRealtime() - started
    }

    private fun log(scenario: String, message: String) = Log.i(TAG, "profile=$profile scenario=$scenario $message")

    companion object {
        const val TAG = "ConnectivityScenario"
    }
}
