package com.example.client.auth

import com.example.client.network.AuthApiService
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class AuthRepositoryImplTest {

    private lateinit var server: MockWebServer
    private lateinit var storage: FakeTokenStorage
    private lateinit var sessionManager: SessionManager
    private lateinit var repository: AuthRepositoryImpl

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        storage = FakeTokenStorage()
        sessionManager = SessionManager(storage)
        val api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(AuthApiService::class.java)
        repository = AuthRepositoryImpl(api, sessionManager)
    }

    @After
    fun tearDown() {
        try { server.shutdown() } catch (_: Exception) { }
    }

    private fun json(body: String, code: Int = 200) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    @Test
    fun success_postsCredentials_andStartsAPersistedSessionWithTheReturnedRole() = runBlocking {
        server.enqueue(json("""{"token":"jwt-abc","role":"VETTING"}"""))

        val result = repository.login("agent", "s3cret")

        assertEquals(LoginResult.Success, result)
        assertEquals(UserRole.VETTING, sessionManager.currentRole.value)
        assertEquals("jwt-abc", storage.load()?.token)
        val request = server.takeRequest()
        assertEquals("/api/auth/login", request.path)
        assertEquals("""{"username":"agent","password":"s3cret"}""", request.body.readUtf8())
    }

    @Test
    fun roleMatching_isCaseInsensitive() = runBlocking {
        server.enqueue(json("""{"token":"jwt-abc","role":"admin"}"""))

        assertEquals(LoginResult.Success, repository.login("a", "b"))
        assertEquals(UserRole.ADMIN, sessionManager.currentRole.value)
    }

    @Test
    fun wrongCredentials_401_isInvalidCredentials_andStoresNothing() = runBlocking {
        server.enqueue(json("""{"error":"bad"}""", 401))

        assertEquals(LoginResult.InvalidCredentials, repository.login("a", "wrong"))
        assertNull(storage.load())
        assertNull(sessionManager.currentRole.value)
    }

    @Test
    fun serverError_500_isServerError() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))

        assertEquals(LoginResult.ServerError, repository.login("a", "b"))
    }

    @Test
    fun unknownRole_isServerError_andStoresNothing() = runBlocking {
        server.enqueue(json("""{"token":"jwt-abc","role":"SUPERUSER"}"""))

        assertEquals(LoginResult.ServerError, repository.login("a", "b"))
        assertNull(storage.load())
    }

    @Test
    fun missingToken_isServerError() = runBlocking {
        server.enqueue(json("""{"role":"ADMIN"}"""))

        assertEquals(LoginResult.ServerError, repository.login("a", "b"))
        assertNull(sessionManager.currentRole.value)
    }

    @Test
    fun malformedBody_isServerError() = runBlocking {
        server.enqueue(json("not json"))

        assertEquals(LoginResult.ServerError, repository.login("a", "b"))
    }

    @Test
    fun unreachableServer_isNetworkError() = runBlocking {
        server.shutdown()

        assertEquals(LoginResult.NetworkError, repository.login("a", "b"))
        assertNull(sessionManager.currentRole.value)
    }
}
