package com.example.client.network

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

/** Request body for `POST /api/auth/login`. Overrides toString so the password is never logged. */
class LoginRequest(val username: String, val password: String) {
    override fun toString() = "LoginRequest(username=$username)"
}

/**
 * Response body of `POST /api/auth/login`: the JWT, the role it was issued for, and the user's CBO (collectors only).
 * Contract assumed until the backend endpoint (#30) lands; confirm role naming there.
 */
class LoginResponse(val token: String?, val role: String?, val cboId: String? = null) {
    override fun toString() = "LoginResponse(role=$role)"
}

interface AuthApiService {
    @POST("/api/auth/login")
    suspend fun login(@Body request: LoginRequest): Response<LoginResponse>
}
