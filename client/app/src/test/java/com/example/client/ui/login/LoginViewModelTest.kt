package com.example.client.ui.login

import com.example.client.auth.AuthRepository
import com.example.client.auth.FakeTokenStorage
import com.example.client.auth.LoginResult
import com.example.client.auth.SessionManager
import com.example.client.auth.UserRole
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelTest {

    private class FakeAuthRepository : AuthRepository {
        var next: CompletableDeferred<LoginResult> = CompletableDeferred(LoginResult.Success)
        val calls = mutableListOf<Pair<String, String>>()

        override suspend fun login(username: String, password: String): LoginResult {
            calls += username to password
            return next.await()
        }
    }

    private val dispatcher = StandardTestDispatcher()
    private lateinit var repository: FakeAuthRepository
    private lateinit var sessionManager: SessionManager
    private lateinit var viewModel: LoginViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repository = FakeAuthRepository()
        sessionManager = SessionManager(FakeTokenStorage())
        viewModel = LoginViewModel(repository, sessionManager)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun fill(user: String = "agent", pass: String = "pw") {
        viewModel.onUsernameChange(user)
        viewModel.onPasswordChange(pass)
    }

    @Test
    fun cannotSubmit_untilBothFieldsAreFilled() = runTest(dispatcher) {
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.canSubmit)

        viewModel.onUsernameChange("agent")
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.canSubmit)

        viewModel.onPasswordChange("pw")
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.canSubmit)
    }

    @Test
    fun submit_withBlankFields_doesNotCallTheRepository() = runTest(dispatcher) {
        viewModel.onSubmit()
        advanceUntilIdle()

        assertTrue(repository.calls.isEmpty())
    }

    @Test
    fun invalidCredentials_showsInlineError_andStopsLoading() = runTest(dispatcher) {
        repository.next = CompletableDeferred(LoginResult.InvalidCredentials)
        fill()

        viewModel.onSubmit()
        advanceUntilIdle()

        assertEquals(LoginError.INVALID_CREDENTIALS, viewModel.uiState.value.error)
        assertFalse(viewModel.uiState.value.isLoading)
    }

    @Test
    fun networkFailure_showsNetworkError() = runTest(dispatcher) {
        repository.next = CompletableDeferred(LoginResult.NetworkError)
        fill()

        viewModel.onSubmit()
        advanceUntilIdle()

        assertEquals(LoginError.NETWORK, viewModel.uiState.value.error)
    }

    @Test
    fun serverFailure_showsServerError() = runTest(dispatcher) {
        repository.next = CompletableDeferred(LoginResult.ServerError)
        fill()

        viewModel.onSubmit()
        advanceUntilIdle()

        assertEquals(LoginError.SERVER, viewModel.uiState.value.error)
    }

    @Test
    fun editingAField_clearsTheError() = runTest(dispatcher) {
        repository.next = CompletableDeferred(LoginResult.InvalidCredentials)
        fill()
        viewModel.onSubmit()
        advanceUntilIdle()

        viewModel.onPasswordChange("pw2")
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun whileInFlight_isLoading_andDoubleSubmitIsIgnored() = runTest(dispatcher) {
        repository.next = CompletableDeferred() // never completes until we say so
        fill()

        viewModel.onSubmit()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isLoading)

        viewModel.onSubmit()
        advanceUntilIdle()
        assertEquals(1, repository.calls.size)

        repository.next.complete(LoginResult.InvalidCredentials)
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isLoading)
    }

    @Test
    fun success_clearsThePassword_andTrimsTheUsernameSent() = runTest(dispatcher) {
        fill(user = "  agent  ", pass = "pw")

        viewModel.onSubmit()
        advanceUntilIdle()

        assertEquals("agent", repository.calls.single().first)
        assertEquals("", viewModel.uiState.value.password)
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun showsSessionExpiredNotice_afterA401Logout() = runTest(dispatcher) {
        sessionManager.startSession("jwt-1", UserRole.ADMIN)
        sessionManager.expireSession("jwt-1")
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.sessionExpired)
    }
}
