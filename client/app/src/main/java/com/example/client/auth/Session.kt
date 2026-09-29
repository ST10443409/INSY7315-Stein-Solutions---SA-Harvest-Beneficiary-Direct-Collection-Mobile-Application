package com.example.client.auth

/** An authenticated session: the JWT and the role the backend issued it for. */
class Session(val token: String, val role: UserRole) {
    // Never print the token (it would end up in logs).
    override fun toString() = "Session(role=$role)"
}
