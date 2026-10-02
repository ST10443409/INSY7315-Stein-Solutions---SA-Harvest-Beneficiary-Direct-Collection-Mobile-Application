package com.example.client.sync

import com.example.client.data.local.entity.CboCollectionEntity
import com.example.client.data.local.entity.SyncStatus
import com.example.client.network.CboSyncRecordResult
import com.example.client.network.CboSyncRequest
import com.example.client.network.CboSyncResponse
import com.example.client.network.ApiEnvelope
import com.example.client.network.HttpClients
import com.example.client.network.SyncApiService
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.GzipSource
import okio.buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * The CBO sync under the network conditions it was built for (#55), over a real socket: the production OkHttp set-up
 * ([HttpClients]) and the real processor, against a stand-in backend that is idempotent on record ids like the real one.
 * Whatever the network does, a record must never be lost, never be marked failed because of connectivity, and every
 * record must end up synced exactly once on the server once the network is back.
 *
 * The on-device version of these scenarios (airplane mode, emulator 2G profiles, the real backend) is
 * androidTest/.../ConnectivityScenarioTest; results are in docs/performance/low-connectivity.md.
 */
class SyncUnderPoorConnectivityTest {

    /** Stands in for POST /api/cbo-collection/sync: stores each id once, answers "already received" for repeats. */
    private class FakeBackend : Dispatcher() {
        val stored: MutableSet<String> = Collections.synchronizedSet(LinkedHashSet())
        val requests: MutableList<RecordedRequest> = Collections.synchronizedList(mutableListOf())

        /** What the network does to a request once the server has read it (null: answers normally). */
        @Volatile
        var fault: ((requestNumber: Int) -> SocketPolicy?)? = null

        private val gson = Gson()

        // MockWebServer asks this before reading a request body, which is when a "during the upload" fault must be known.
        // A request cut off mid-upload is never dispatched, so the same request number is asked about again next time.
        override fun peek(): MockResponse = MockResponse().apply { fault?.invoke(requests.size + 1)?.let { socketPolicy = it } }

        override fun dispatch(request: RecordedRequest): MockResponse {
            requests += request
            val raw = request.body.clone()
            val json = if (request.getHeader("Content-Encoding") == "gzip") GzipSource(raw).buffer().readUtf8() else raw.readUtf8()
            val batch = gson.fromJson(json, CboSyncRequest::class.java)
            val results = batch.records.map { CboSyncRecordResult(it.id, success = true, alreadyReceived = !stored.add(it.id)) }

            val response = MockResponse().setBody(gson.toJson(ApiEnvelope(success = true, data = CboSyncResponse(results))))
            fault?.invoke(requests.size)?.let { response.setSocketPolicy(it) }
            return response
        }
    }

    private lateinit var server: MockWebServer
    private lateinit var backend: FakeBackend
    private lateinit var dao: CboSyncProcessorTest.FakeCollectionDao

    @Before
    fun setUp() {
        backend = FakeBackend()
        server = MockWebServer().apply { dispatcher = backend; start() }
        dao = CboSyncProcessorTest.FakeCollectionDao()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun processor(client: OkHttpClient = HttpClients.builder().build()): CboSyncProcessor {
        val api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(GsonConverterFactory.create())
            .client(client)
            .build()
            .create(SyncApiService::class.java)
        return CboSyncProcessor(dao, CboSyncProcessorTest.FakeProductLineDao(), api)
    }

    private fun savePending(count: Int) {
        dao.rows.value = (1..count).map { i ->
            CboCollectionEntity(
                id = "rec-%03d".format(i), cboId = "cbo-test-001", arrivalTime = "09:15", departureTime = "10:05",
                donorName = "Shoprite Mitchells Plain", donorSigned = true, cboSigned = true, deliveryNote = "DN-%06d".format(i),
                noteAttached = true, collectNotes = "Collected from the back entrance; manager on duty signed.",
                shots = listOf(true, true, false, false), latitude = -34.0386, longitude = 18.6774
            )
        }
    }

    private fun statuses() = dao.rows.value.groupingBy { it.syncStatus }.eachCount()

    /** Nothing lost, nothing marked failed, no retry budget used: connectivity is never the record's fault. */
    private fun assertUntouched(expectedPending: Int) {
        assertEquals(expectedPending, dao.rows.value.count { it.syncStatus == SyncStatus.PENDING })
        assertEquals(0, dao.rows.value.count { it.syncStatus == SyncStatus.FAILED })
        assertTrue(dao.rows.value.all { it.retryCount == 0 })
    }

    @Test
    fun noConnection_recordsStayPending_andAllSyncOnceItIsBack() = runBlocking {
        savePending(120)
        val port = server.port
        server.shutdown() // nothing listening: like airplane mode, the connection is refused at once

        assertEquals(SyncRunResult.RETRY_LATER, processor().syncPending("tester"))
        assertUntouched(expectedPending = 120)

        server = MockWebServer().apply { dispatcher = backend; start(port) }
        assertEquals(SyncRunResult.DONE, processor().syncPending("tester"))

        assertEquals(mapOf(SyncStatus.SYNCED to 120), statuses())
        assertEquals(120, backend.stored.size)
    }

    @Test
    fun connectionLostMidSync_afterTheServerStoredABatch_resumesWithoutDuplicates() = runBlocking {
        savePending(120) // three batches: 50, 50, 20
        // The first batch gets through; then the network goes: the server receives and stores what is sent, but no
        // answer reaches the phone (the worst case: the phone cannot know the second batch arrived).
        backend.fault = { n -> if (n >= 2) SocketPolicy.DISCONNECT_AFTER_REQUEST else null }

        assertEquals(SyncRunResult.RETRY_LATER, processor().syncPending("tester"))

        assertEquals(50, dao.rows.value.count { it.syncStatus == SyncStatus.SYNCED })
        assertUntouched(expectedPending = 70)
        assertEquals(100, backend.stored.size) // the server did get the second batch

        backend.fault = null
        assertEquals(SyncRunResult.DONE, processor().syncPending("tester"))

        assertEquals(mapOf(SyncStatus.SYNCED to 120), statuses())
        assertEquals(120, backend.stored.size) // the resent batch was "already received", not stored twice
    }

    @Test
    fun connectionLostWhileUploading_resumesAndCompletes() = runBlocking {
        savePending(120)
        backend.fault = { n -> if (n >= 2) SocketPolicy.DISCONNECT_DURING_REQUEST_BODY else null }

        assertEquals(SyncRunResult.RETRY_LATER, processor().syncPending("tester"))
        assertUntouched(expectedPending = 70)

        backend.fault = null
        assertEquals(SyncRunResult.DONE, processor().syncPending("tester"))

        assertEquals(mapOf(SyncStatus.SYNCED to 120), statuses())
        assertEquals(120, backend.stored.size)
    }

    @Test
    fun aStalledConnection_givesUpAfterTheReadTimeout_andKeepsEverything() = runBlocking {
        savePending(10)
        backend.fault = { SocketPolicy.NO_RESPONSE } // connected, but nothing ever comes back ("lie-fi")
        // Production waits READ_TIMEOUT_SECONDS; a shorter one keeps the test quick without changing the behaviour.
        val client = HttpClients.builder().readTimeout(1, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()

        val started = System.nanoTime()
        assertEquals(SyncRunResult.RETRY_LATER, processor(client).syncPending("tester"))

        assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started) < 10)
        assertUntouched(expectedPending = 10)
    }

    @Test
    fun aBatch_goesOverTheWireGzipped_atUnderAFifthOfItsSize() = runBlocking {
        savePending(50)

        processor().syncPending("tester")

        val request = backend.requests.single()
        assertEquals("gzip", request.getHeader("Content-Encoding"))
        val json = GzipSource(request.body.clone()).buffer().readUtf8()
        val ratio = request.bodySize.toDouble() / json.toByteArray().size
        println("Batch of 50: ${json.toByteArray().size} bytes of JSON, ${request.bodySize} bytes sent (${"%.0f".format(ratio * 100)}%)")
        assertTrue("sent $ratio of the JSON size", ratio < 0.2)
    }

    @Test
    fun aThrottledUplink_stillCompletes_withTheProductionTimeouts() = runBlocking {
        savePending(100) // two batches
        // A quarter of a second per 2 KB: a 2G-like trickle, sped up 4x so the test stays short.
        server.shutdown()
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = backend.dispatch(request).throttleBody(2048, 250, TimeUnit.MILLISECONDS)
            }
            start()
        }

        assertEquals(SyncRunResult.DONE, processor().syncPending("tester"))
        assertEquals(mapOf(SyncStatus.SYNCED to 100), statuses())
    }
}
