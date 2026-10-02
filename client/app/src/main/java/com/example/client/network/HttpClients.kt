package com.example.client.network

import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.Response
import okio.Buffer
import okio.BufferedSink
import okio.GzipSink
import okio.buffer
import java.util.concurrent.TimeUnit

/**
 * The OkHttp settings every API call uses, sized for the 2G/3G links collectors and vetting officers actually have
 * (#55). NetworkModule adds the session pieces on top; the connectivity tests build their client from here too, so they
 * exercise exactly what ships. See docs/performance/low-connectivity.md for the measurements behind the numbers.
 */
object HttpClients {
    /** TCP + TLS set-up on GPRS/GSM (150-550 ms per round trip, with retransmits) can take well over OkHttp's default 10 s. */
    const val CONNECT_TIMEOUT_SECONDS = 20L

    /**
     * How long a read may wait for the next byte. It also covers the time a request body sits in the socket's send
     * buffer before the server has it all: OkHttp considers a 59 KB batch "written" long before 2G has carried it, and
     * the default 10 s then expired while the upload was still draining, so the same batch failed on every retry.
     */
    const val READ_TIMEOUT_SECONDS = 60L

    /** How long one write to the socket may block. */
    const val WRITE_TIMEOUT_SECONDS = 60L

    fun builder(): OkHttpClient.Builder = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .addInterceptor(GzipRequestInterceptor())
}

/**
 * Gzips request bodies of at least [minBytes] (a sync batch of 50 collections goes from about 59 KB to about 9 KB), and
 * marks them `Content-Encoding: gzip`, which the backend decompresses (UseRequestDecompression). Small bodies such as the
 * login request are sent as they are: compressing them saves nothing. Response compression needs nothing here: OkHttp
 * already asks for gzip and unpacks it.
 */
class GzipRequestInterceptor(private val minBytes: Long = MIN_BYTES) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val body = request.body
        if (body == null || request.header(CONTENT_ENCODING) != null || body.isOneShot() || body.isDuplex()) {
            return chain.proceed(request)
        }
        val length = body.contentLength()
        if (length in 0 until minBytes) return chain.proceed(request)

        return chain.proceed(
            request.newBuilder()
                .header(CONTENT_ENCODING, "gzip")
                .method(request.method, gzip(body))
                .build()
        )
    }

    // Compressed up front, so the request has a Content-Length (no chunked upload) and can be replayed if OkHttp retries.
    private fun gzip(body: RequestBody): RequestBody {
        val compressed = Buffer()
        GzipSink(compressed).buffer().use { body.writeTo(it) }
        return object : RequestBody() {
            override fun contentType(): MediaType? = body.contentType()
            override fun contentLength(): Long = compressed.size
            override fun writeTo(sink: BufferedSink) {
                sink.write(compressed.copy(), compressed.size)
            }
        }
    }

    companion object {
        const val CONTENT_ENCODING = "Content-Encoding"
        const val MIN_BYTES = 1024L
    }
}
