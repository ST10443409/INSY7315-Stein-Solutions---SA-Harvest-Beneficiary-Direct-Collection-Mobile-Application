package com.example.client.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.client.auth.AuthRepository
import com.example.client.auth.LoginResult
import com.example.client.auth.SessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class LoginError { INVALID_CREDENTIALS, NETWORK, SERVER }

data class LoginUiState(
    val username: String = "",
    val password: String = "",
    val isLoading: Boolean = false,
    val error: LoginError? = null,
    val sessionExpired: Boolean = false
) {
    val canSubmit: Boolean get() = username.isNotBlank() && password.isNotEmpty() && !isLoading

    // Keep the password out of any accidental logging of the state.
    override fun toString() = "LoginUiState(username=$username, isLoading=$isLoading, error=$error)"
}

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    sessionManager: SessionManager
) : ViewModel() {

    private val form = MutableStateFlow(LoginUiState())

    val uiState: StateFlow<LoginUiState> =
        combine(form, sessionManager.sessionExpired) { state, expired ->
            state.copy(sessionExpired = expired)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, LoginUiState())

    fun onUsernameChange(value: String) = form.update { it.copy(username = value, error = null) }

    fun onPasswordChange(value: String) = form.update { it.copy(password = value, error = null) }

    fun onSubmit() {
        val current = form.value
        if (!current.canSubmit) return
        form.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            val result = authRepository.login(current.username.trim(), current.password)
            form.update {
                when (result) {
                    // The session flips the nav shell away from login; drop the credentials.
                    LoginResult.Success -> LoginUiState(username = it.username)
                    LoginResult.InvalidCredentials -> it.copy(isLoading = false, error = LoginError.INVALID_CREDENTIALS)
                    LoginResult.NetworkError -> it.copy(isLoading = false, error = LoginError.NETWORK)
                    LoginResult.ServerError -> it.copy(isLoading = false, error = LoginError.SERVER)
                }
            }
        }
    }
}
