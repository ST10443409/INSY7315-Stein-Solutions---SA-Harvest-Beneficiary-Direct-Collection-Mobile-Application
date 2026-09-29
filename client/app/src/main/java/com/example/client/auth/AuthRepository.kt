package com.example.client.auth

sealed interface LoginResult {
    object Success : LoginResult
    object InvalidCredentials : LoginResult
    object NetworkError : LoginResult
    object ServerError : LoginResult
}

interface AuthRepository {
    /** Signs in and, on success, starts (and persists) the session. Never throws for expected failures. */
    suspend fun login(username: String, password: String): LoginResult
}
