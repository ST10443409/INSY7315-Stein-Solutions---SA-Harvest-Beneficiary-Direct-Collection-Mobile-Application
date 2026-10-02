package com.example.client.auth

sealed interface LoginResult {
    object Success : LoginResult
    object InvalidCredentials : LoginResult
    object NetworkError : LoginResult
    object ServerError : LoginResult

    /** The server is limiting sign-in attempts from this address (429); trying again shortly will work. */
    object TooManyAttempts : LoginResult
}

interface AuthRepository {
    /** Signs in and, on success, starts (and persists) the session. Never throws for expected failures. */
    suspend fun login(username: String, password: String): LoginResult
}
