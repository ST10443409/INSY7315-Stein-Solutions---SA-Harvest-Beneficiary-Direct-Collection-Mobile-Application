package com.example.client.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stand-in [RoleProvider] with a hardcoded role. There is no authentication here.
 * TODO(#27): replace with an implementation that derives the role from the stored auth token.
 *
 * Change [STUB_ROLE] and rebuild to exercise each role's navigation graph.
 */
@Singleton
class StubRoleProvider @Inject constructor() : RoleProvider {

    private val role = MutableStateFlow<UserRole?>(STUB_ROLE)

    override val currentRole: StateFlow<UserRole?> = role.asStateFlow()

    companion object {
        val STUB_ROLE = UserRole.CBO_COLLECTION
    }
}
