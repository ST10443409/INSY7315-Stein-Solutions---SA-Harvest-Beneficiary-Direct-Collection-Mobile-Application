package com.example.client.auth

/**
 * An authenticated session: the JWT, the role the backend issued it for, and the CBO the user collects for
 * ([cboId]; null for users without one, such as Vetting and Admin), and the username they signed in with ([username]; the
 * officer id on a vetting decision). Both are null for a session restored from before they were kept.
 */
class Session(val token: String, val role: UserRole, val cboId: String? = null, val username: String? = null) {
    // Never print the token (it would end up in logs).
    override fun toString() = "Session(role=$role)"
}
