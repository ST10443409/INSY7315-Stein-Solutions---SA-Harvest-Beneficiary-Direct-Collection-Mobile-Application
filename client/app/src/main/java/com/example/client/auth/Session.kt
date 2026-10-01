package com.example.client.auth

/**
 * An authenticated session: the JWT, the role the backend issued it for, and the CBO the user collects for
 * ([cboId]; null for users without one, such as Vetting and Admin).
 */
class Session(val token: String, val role: UserRole, val cboId: String? = null) {
    // Never print the token (it would end up in logs).
    override fun toString() = "Session(role=$role)"
}
