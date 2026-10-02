package com.example.client.ui.admin

import com.example.client.ui.navigation.Routes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdminDestinationsTest {

    @Test
    fun theOverviewOffersTheThreeOversightSections_inThatOrder() {
        assertEquals(
            listOf(AdminDestination.SYNC_MONITOR, AdminDestination.FAILED_SYNC, AdminDestination.USER_ACTIVITY),
            AdminDestination.values().toList()
        )
    }

    @Test
    fun everyDestination_hasItsOwnAdminOnlyRoute() {
        val routes = AdminDestination.values().map { it.route }
        assertEquals(routes.size, routes.toSet().size)
        assertTrue("an Admin route must be namespaced, so it can never collide with another role's", routes.all { it.startsWith("admin_") })
        assertEquals(
            setOf(Routes.ADMIN_SYNC_MONITOR, Routes.ADMIN_FAILED_SYNC, Routes.ADMIN_USER_ACTIVITY),
            routes.toSet()
        )
    }

    @Test
    fun everyDestination_hasItsOwnTitleAndDescription() {
        val titles = AdminDestination.values().map { it.title }
        val descriptions = AdminDestination.values().map { it.description }
        assertEquals(titles.size, titles.toSet().size)
        assertEquals(descriptions.size, descriptions.toSet().size)
        assertTrue((titles + descriptions).all { it != 0 })
    }

    @Test
    fun everyAlert_opensAnOversightSection() {
        assertTrue(AdminAlertKind.values().all { it.destination in AdminDestination.values() })
    }
}
