package com.example.client.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single owner of the authenticated session. Restores it from [TokenStorage] on start-up,
 * so the token and role survive a process restart, and is the [RoleProvider] that drives
 * the navigation shell: when the session ends, [currentRole] becomes null and the UI
 * falls back to the login screen.
 */
@Singleton
class SessionManager @Inject constructor(
    private val storage: TokenStorage
) : RoleProvider {

    private val session = MutableStateFlow(storage.load())
    private val role = MutableStateFlow(session.value?.role)
    private val expired = MutableStateFlow(false)

    override val currentRole: StateFlow<UserRole?> = role.asStateFlow()

    /** True after the server rejected our token; cleared by the next sign-in. */
    val sessionExpired: StateFlow<Boolean> = expired.asStateFlow()

    /** The bearer token for outgoing requests, or null when signed out. */
    fun token(): String? = session.value?.token

    @Synchronized
    fun startSession(token: String, userRole: UserRole) {
        val newSession = Session(token, userRole)
        storage.save(newSession)
        expired.value = false
        session.value = newSession
        role.value = userRole
    }

    /** User-initiated sign out. */
    @Synchronized
    fun endSession() {
        expired.value = false
        clear()
    }

    /**
     * The server answered 401 to a request that carried [rejectedToken]. Ends the session only
     * if that token is still the current one, so a late 401 for an old token cannot sign out
     * a user who has just logged in again.
     */
    @Synchronized
    fun expireSession(rejectedToken: String) {
        if (session.value?.token != rejectedToken) return
        expired.value = true
        clear()
    }

    private fun clear() {
        storage.clear()
        session.value = null
        role.value = null
    }
}
