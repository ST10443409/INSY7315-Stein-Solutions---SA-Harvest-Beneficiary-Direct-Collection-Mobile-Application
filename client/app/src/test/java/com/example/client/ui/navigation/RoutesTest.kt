package com.example.client.ui.navigation

import com.example.client.auth.UserRole
import org.junit.Assert.assertEquals
import org.junit.Test

class RoutesTest {

    @Test
    fun userRole_hasExactlyTheThreeExpectedValues() {
        assertEquals(
            listOf("CBO_COLLECTION", "VETTING", "ADMIN"),
            UserRole.values().map { it.name }
        )
    }

    @Test
    fun startDestination_mapsEachRoleToItsLandingScreen() {
        assertEquals(Routes.CBO_FORM1, UserRole.CBO_COLLECTION.startDestination())
        assertEquals(Routes.VETTING_FORM2, UserRole.VETTING.startDestination())
        assertEquals(Routes.ADMIN_DASHBOARD, UserRole.ADMIN.startDestination())
    }

    @Test
    fun graphRoute_isDistinctPerRole() {
        val graphs = UserRole.values().map { it.graphRoute() }
        assertEquals(graphs.size, graphs.toSet().size)
    }
}

