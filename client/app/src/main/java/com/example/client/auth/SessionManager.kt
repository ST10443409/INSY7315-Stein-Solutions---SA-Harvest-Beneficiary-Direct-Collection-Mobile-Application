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

    // A session restored without a username (kept from before it was recorded) cannot be tied to the records its owner
    // captures, so it is ended and the user signs in again (#70): nothing may be attributed to "nobody".
    private val session = MutableStateFlow(restore(storage))
    private val role = MutableStateFlow(session.value?.role)
    private val expired = MutableStateFlow(false)

    override val currentRole: StateFlow<UserRole?> = role.asStateFlow()

    /** True after the server rejected our token; cleared by the next sign-in. */
    val sessionExpired: StateFlow<Boolean> = expired.asStateFlow()

    /** The bearer token for outgoing requests, or null when signed out. */
    fun token(): String? = session.value?.token

    /** The CBO the signed-in user collects for, or null when signed out or the user has none. */
    fun cboId(): String? = session.value?.cboId

    /** The username the signed-in user logged in with, or null when signed out. */
    fun username(): String? = session.value?.username

    override fun currentUsername(): String? = username()

    @Synchronized
    fun startSession(token: String, userRole: UserRole, cboId: String? = null, username: String? = null) {
        val newSession = Session(token, userRole, cboId, username)
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

    private companion object {
        fun restore(storage: TokenStorage): Session? {
            val stored = storage.load() ?: return null
            if (!stored.username.isNullOrBlank()) return stored
            storage.clear()
            return null
        }
    }
}
