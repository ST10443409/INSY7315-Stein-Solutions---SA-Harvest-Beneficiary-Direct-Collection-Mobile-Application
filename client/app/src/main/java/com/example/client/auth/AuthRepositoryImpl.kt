package com.example.client.auth

import com.example.client.network.AuthApiService
import com.example.client.network.LoginRequest
import com.google.gson.JsonParseException
import com.google.gson.stream.MalformedJsonException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepositoryImpl @Inject constructor(
    private val api: AuthApiService,
    private val sessionManager: SessionManager
) : AuthRepository {

    override suspend fun login(username: String, password: String): LoginResult = try {
        val response = api.login(LoginRequest(username, password))
        when {
            response.isSuccessful -> startSession(response.body()?.token, response.body()?.role)
            response.code() == 400 || response.code() == 401 -> LoginResult.InvalidCredentials
            else -> LoginResult.ServerError
        }
    } catch (e: MalformedJsonException) {
        LoginResult.ServerError // an IOException subclass, but the server answered: not a connectivity problem
    } catch (e: IOException) {
        LoginResult.NetworkError
    } catch (e: JsonParseException) {
        LoginResult.ServerError
    }

    private fun startSession(token: String?, roleName: String?): LoginResult {
        val role = UserRole.values().firstOrNull { it.name.equals(roleName, ignoreCase = true) }
        // A token without a usable role would leave the user with nowhere to route; treat as a bad response.
        if (token.isNullOrBlank() || role == null) return LoginResult.ServerError
        sessionManager.startSession(token, role)
        return LoginResult.Success
    }
}
