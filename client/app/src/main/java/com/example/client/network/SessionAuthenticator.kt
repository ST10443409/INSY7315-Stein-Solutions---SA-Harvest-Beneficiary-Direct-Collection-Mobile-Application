package com.example.client.network

import com.example.client.auth.SessionManager
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import javax.inject.Inject

/**
 * The one place a 401 is handled. OkHttp calls this for every 401 response; if the request
 * carried our bearer token, the token is expired or revoked, so the session is ended and the
 * UI returns to login. We never retry (there is no refresh token), hence always `null`.
 *
 * A 401 on a request without a bearer token (e.g. wrong password at login) is not a session
 * problem and is left for the caller to report.
 */
class SessionAuthenticator @Inject constructor(
    private val sessionManager: SessionManager
) : Authenticator {

    override fun authenticate(route: Route?, response: Response): Request? {
        val sentToken = response.request.header(AuthInterceptor.AUTHORIZATION)
            ?.removePrefix("Bearer ")
            ?: return null
        sessionManager.expireSession(sentToken)
        return null
    }
}
