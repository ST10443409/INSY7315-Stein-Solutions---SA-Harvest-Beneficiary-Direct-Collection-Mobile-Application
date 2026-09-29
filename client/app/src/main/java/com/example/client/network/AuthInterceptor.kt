package com.example.client.network

import com.example.client.auth.SessionManager
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject

/** Adds `Authorization: Bearer <token>` to every outgoing request while a session exists. */
class AuthInterceptor @Inject constructor(
    private val sessionManager: SessionManager
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val token = sessionManager.token()
        if (token == null || request.header(AUTHORIZATION) != null) {
            return chain.proceed(request)
        }
        return chain.proceed(
            request.newBuilder().header(AUTHORIZATION, "Bearer $token").build()
        )
    }

    companion object {
        const val AUTHORIZATION = "Authorization"
    }
}
