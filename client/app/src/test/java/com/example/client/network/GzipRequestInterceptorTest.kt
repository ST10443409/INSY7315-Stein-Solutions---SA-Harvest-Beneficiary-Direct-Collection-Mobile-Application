package com.example.client.network

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.GzipSource
import okio.buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** Request compression (#55), as the server receives it through the production client. */
class GzipRequestInterceptorTest {
    private lateinit var server: MockWebServer
    private val client = HttpClients.builder().build()
    private val json = "application/json".toMediaType()

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() = server.shutdown()

    private fun post(body: String, contentEncoding: String? = null) {
        server.enqueue(MockResponse())
        val request = Request.Builder().url(server.url("/api/x")).post(body.toRequestBody(json))
        if (contentEncoding != null) request.header("Content-Encoding", contentEncoding)
        client.newCall(request.build()).execute().close()
    }

    @Test
    fun aLargeBody_isSentGzipped_withALength_andUnpacksToTheSameJson() {
        val body = """{"records":[${(1..50).joinToString(",") { """{"id":"rec-$it","donorName":"Spar Gugulethu"}""" }}]}"""

        post(body)

        val received = server.takeRequest()
        assertEquals("gzip", received.getHeader("Content-Encoding"))
        assertEquals(received.bodySize.toString(), received.getHeader("Content-Length")) // not a chunked upload
        assertNull(received.getHeader("Transfer-Encoding"))
        assertEquals(body, GzipSource(received.body).buffer().readUtf8())
    }

    @Test
    fun aSmallBody_suchAsTheLogin_isSentAsItIs() {
        val body = """{"username":"cbo_test_user","password":"a password"}"""

        post(body)

        val received = server.takeRequest()
        assertNull(received.getHeader("Content-Encoding"))
        assertEquals(body, received.body.readUtf8())
    }

    @Test
    fun aBodyThatIsAlreadyEncoded_isLeftAlone() {
        val body = "x".repeat(4096)

        post(body, contentEncoding = "identity")

        val received = server.takeRequest()
        assertEquals("identity", received.getHeader("Content-Encoding"))
        assertEquals(body, received.body.readUtf8())
    }

    // Photos and signatures are JPEG/PNG, compressed already: they must reach the server byte for byte (it checks what the bytes
    // really are), and gzip would only cost battery for next to no saving.
    @Test
    fun anImage_isNeverGzipped_evenWhenLarge() {
        val bytes = ByteArray(300_000) { (it % 251).toByte() } // large and very compressible, so gzip WOULD have been applied
        server.enqueue(MockResponse())

        client.newCall(
            Request.Builder().url(server.url("/api/cbo-collection/c/attachments/a"))
                .put(bytes.toRequestBody("image/jpeg".toMediaType())).build()
        ).execute().close()

        val received = server.takeRequest()
        assertNull(received.getHeader("Content-Encoding"))
        assertEquals(bytes.size.toLong(), received.bodySize)
        assertEquals("image/jpeg", received.getHeader("Content-Type"))
        assertEquals(bytes.toList(), received.body.readByteArray().toList())
    }

    @Test
    fun aPng_isNeverGzipped_either() {
        val bytes = ByteArray(5_000) { 1 }
        server.enqueue(MockResponse())

        client.newCall(
            Request.Builder().url(server.url("/api/x")).put(bytes.toRequestBody("image/png".toMediaType())).build()
        ).execute().close()

        assertNull(server.takeRequest().getHeader("Content-Encoding"))
    }
}
