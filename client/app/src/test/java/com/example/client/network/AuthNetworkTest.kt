package com.example.client.network

import com.example.client.auth.FakeTokenStorage
import com.example.client.auth.SessionManager
import com.example.client.auth.UserRole
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Exercises the real interceptor + authenticator against a local mock server. */
class AuthNetworkTest {

    private lateinit var server: MockWebServer
    private lateinit var sessionManager: SessionManager
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        sessionManager = SessionManager(FakeTokenStorage())
        client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(sessionManager))
            .authenticator(SessionAuthenticator(sessionManager))
            .build()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun call(path: String = "/api/anything") =
        client.newCall(Request.Builder().url(server.url(path)).build()).execute()

    @Test
    fun attachesBearerToken_toEveryRequest_whenSignedIn() {
        sessionManager.startSession("jwt-1", UserRole.ADMIN)
        server.enqueue(MockResponse().setResponseCode(200))
        server.enqueue(MockResponse().setResponseCode(200))

        call("/api/a").close()
        call("/api/b").close()

        assertEquals("Bearer jwt-1", server.takeRequest().getHeader("Authorization"))
        assertEquals("Bearer jwt-1", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun sendsNoAuthorizationHeader_whenSignedOut() {
        server.enqueue(MockResponse().setResponseCode(200))

        call().close()

        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun a401_onAnAuthenticatedRequest_endsTheSession_andIsNotRetried() {
        sessionManager.startSession("jwt-1", UserRole.CBO_COLLECTION)
        server.enqueue(MockResponse().setResponseCode(401))

        val response = call()
        response.close()

        assertEquals(401, response.code)
        assertEquals(1, server.requestCount)
        assertNull(sessionManager.currentRole.value)
        assertNull(sessionManager.token())
        assertTrue(sessionManager.sessionExpired.value)
    }

    @Test
    fun a401_withoutABearerToken_isNotTreatedAsAnExpiredSession() {
        // e.g. wrong password on the login call while signed out
        server.enqueue(MockResponse().setResponseCode(401))

        call("/api/auth/login").close()

        assertFalse(sessionManager.sessionExpired.value)
    }

    @Test
    fun aNon401Error_doesNotEndTheSession() {
        sessionManager.startSession("jwt-1", UserRole.ADMIN)
        server.enqueue(MockResponse().setResponseCode(500))

        call().close()

        assertEquals(UserRole.ADMIN, sessionManager.currentRole.value)
    }
}
