package com.example.client.auth

import kotlinx.coroutines.flow.StateFlow

/**
 * Source of who is signed in: their [UserRole] and their username.
 *
 * `null` means no role is available (not signed in). The navigation shell only
 * depends on this interface, so the real token-derived implementation (#27) can
 * replace [StubRoleProvider] without touching the nav graph.
 */
interface RoleProvider {
    val currentRole: StateFlow<UserRole?>

    /**
     * The username of the signed-in user, or null when signed out. The sync workers send only the records this person
     * captured (#70): the server attributes a record to whoever's token sends it, so sending someone else's would put the
     * wrong name on it.
     */
    fun currentUsername(): String?
}
