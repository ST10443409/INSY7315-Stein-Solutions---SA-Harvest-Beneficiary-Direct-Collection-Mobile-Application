package com.example.client.auth

import kotlinx.coroutines.flow.StateFlow

/**
 * Source of the current user's [UserRole].
 *
 * `null` means no role is available (not signed in). The navigation shell only
 * depends on this interface, so the real token-derived implementation (#27) can
 * replace [StubRoleProvider] without touching the nav graph.
 */
interface RoleProvider {
    val currentRole: StateFlow<UserRole?>
}
