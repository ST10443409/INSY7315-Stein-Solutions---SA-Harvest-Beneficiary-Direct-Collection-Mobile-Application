package com.example.client.ui.navigation

import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.navigation.NavDestination
import androidx.navigation.NavGraph
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.client.auth.UserRole
import com.example.client.data.repository.SyncForm
import com.example.client.ui.placeholder.Form1PlaceholderScreen
import com.example.client.ui.placeholder.Form2PlaceholderScreen
import com.example.client.ui.admin.AdminDestination
import com.example.client.ui.admin.AdminTags
import com.example.client.ui.placeholder.ScreenTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoleNavigationTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var navController: NavHostController

    private companion object {
        const val LOGIN_SLOT = "login_slot"
        const val SYNC_MONITOR_SLOT = "sync_monitor_slot"
        const val FAILED_LIST_SLOT = "failed_list_slot"
        const val FAILED_RECORD_SLOT = "failed_record_slot"
    }

    private fun launch(role: UserRole?) {
        composeRule.setContent {
            navController = rememberNavController()
            if (role == null) AppRoot(null, login = { Text("login", Modifier.testTag(LOGIN_SLOT)) }) else AppNavHost(
                role, navController,
                screens = CboScreens(form1 = { Form1PlaceholderScreen() }, syncBadge = {}, mySubmissions = {}),
                vetting = VettingScreens(list = { Form2PlaceholderScreen() }, detail = { _, _ -> }, decision = { }),
                admin = AdminScreens(
                    syncMonitor = { Text("sync monitor", Modifier.testTag(SYNC_MONITOR_SLOT)) },
                    failedSyncList = { _, onOpen ->
                        Text("failed syncs", Modifier.testTag(FAILED_LIST_SLOT).clickable { onOpen(SyncForm.VETTING_DECISION, "record-1") })
                    },
                    failedSyncRecord = { Text("failed sync record", Modifier.testTag(FAILED_RECORD_SLOT)) }
                )
            )
        }
    }

    private fun routesOf(vararg routes: String) = routes.toList()

    private val adminRoutes = listOf(
        Routes.ADMIN_DASHBOARD, Routes.ADMIN_FORM1, Routes.ADMIN_FORM2, Routes.ADMIN_RECORD, Routes.ADMIN_DECISION,
        Routes.ADMIN_SYNC_MONITOR, Routes.ADMIN_FAILED_SYNC, Routes.ADMIN_FAILED_SYNC_RECORD, Routes.ADMIN_USER_ACTIVITY
    )

    // NavGraph.findNode only checks direct children, so walk nested graphs explicitly.
    private fun allDestinations(destination: NavDestination): List<NavDestination> =
        if (destination is NavGraph) listOf(destination) + destination.flatMap { allDestinations(it) }
        else listOf(destination)

    private fun assertOnlyReachable(allowed: List<String>) {
        val everyRoute = listOf(
            Routes.CBO_GRAPH, Routes.CBO_FORM1, Routes.CBO_SUBMISSIONS,
            Routes.VETTING_GRAPH, Routes.VETTING_FORM2, Routes.VETTING_RECORD, Routes.VETTING_DECISION,
            Routes.ADMIN_GRAPH, Routes.ADMIN_DASHBOARD, Routes.ADMIN_FORM1,
            Routes.ADMIN_FORM2, Routes.ADMIN_RECORD, Routes.ADMIN_DECISION, Routes.ADMIN_SYNC_MONITOR,
            Routes.ADMIN_FAILED_SYNC, Routes.ADMIN_FAILED_SYNC_RECORD, Routes.ADMIN_USER_ACTIVITY
        )
        composeRule.runOnUiThread {
            everyRoute.forEach { route ->
                val node = allDestinations(navController.graph).firstOrNull { it.route == route }
                if (route in allowed) assertNotNull("$route should exist", node)
                else assertNull("$route must not exist in this role's graph", node)
            }
        }
    }

    @Test
    fun cboCollection_landsOnForm1_andHasNoRouteToOtherRoles() {
        launch(UserRole.CBO_COLLECTION)

        composeRule.onNodeWithTag(ScreenTags.FORM1).assertIsDisplayed()
        assertOnlyReachable(routesOf(Routes.CBO_GRAPH, Routes.CBO_FORM1, Routes.CBO_SUBMISSIONS))
        composeRule.runOnUiThread {
            assertEquals(Routes.CBO_FORM1, navController.currentDestination?.route)
            assertThrows(IllegalArgumentException::class.java) {
                navController.navigate(Routes.VETTING_FORM2)
            }
            assertThrows(IllegalArgumentException::class.java) {
                navController.navigate(Routes.ADMIN_DASHBOARD)
            }
        }
        composeRule.onNodeWithTag(ScreenTags.FORM1).assertIsDisplayed()
    }

    @Test
    fun vetting_landsOnForm2_andHasNoRouteToOtherRoles() {
        launch(UserRole.VETTING)

        composeRule.onNodeWithTag(ScreenTags.FORM2).assertIsDisplayed()
        assertOnlyReachable(routesOf(Routes.VETTING_GRAPH, Routes.VETTING_FORM2, Routes.VETTING_RECORD, Routes.VETTING_DECISION))
        composeRule.runOnUiThread {
            assertEquals(Routes.VETTING_FORM2, navController.currentDestination?.route)
            assertThrows(IllegalArgumentException::class.java) {
                navController.navigate(Routes.CBO_FORM1)
            }
            assertThrows(IllegalArgumentException::class.java) {
                navController.navigate(Routes.ADMIN_DASHBOARD)
            }
        }
    }

    @Test
    fun admin_landsOnDashboard_withAnEntryForEverythingAnAdminCanOpen() {
        launch(UserRole.ADMIN)

        composeRule.onNodeWithTag(AdminTags.DASHBOARD).assertIsDisplayed()
        AdminDestination.values().forEach { composeRule.onNodeWithTag(AdminTags.entry(it)).performScrollTo().assertIsDisplayed() }
    }

    @Test
    fun admin_canOpenBothWorkflows_andComeBackToTheDashboard() {
        launch(UserRole.ADMIN)

        composeRule.onNodeWithTag(AdminTags.entry(AdminDestination.FORM1)).performClick()
        composeRule.onNodeWithTag(ScreenTags.FORM1).assertIsDisplayed()
        pressBack()
        composeRule.onNodeWithTag(AdminTags.DASHBOARD).assertIsDisplayed()

        composeRule.onNodeWithTag(AdminTags.entry(AdminDestination.FORM2)).performClick()
        composeRule.onNodeWithTag(ScreenTags.FORM2).assertIsDisplayed()
        pressBack()
        composeRule.onNodeWithTag(AdminTags.DASHBOARD).assertIsDisplayed()
    }

    @Test
    fun admin_canOpenEachOversightSection_seeItLabelled_andComeBack() {
        launch(UserRole.ADMIN)

        AdminDestination.placeholders.forEach { destination ->
            composeRule.onNodeWithTag(AdminTags.entry(destination)).performScrollTo().performClick()
            composeRule.onNodeWithTag(AdminTags.section(destination)).assertIsDisplayed()
            composeRule.onNodeWithTag(AdminTags.SECTION_COMING_SOON).assertIsDisplayed()
            pressBack()
            composeRule.onNodeWithTag(AdminTags.DASHBOARD).assertIsDisplayed()
        }
    }

    @Test
    fun admin_canOpenTheSyncMonitor_andComeBack() {
        launch(UserRole.ADMIN)

        composeRule.onNodeWithTag(AdminTags.entry(AdminDestination.SYNC_MONITOR)).performScrollTo().performClick()
        composeRule.onNodeWithTag(SYNC_MONITOR_SLOT).assertIsDisplayed()
        pressBack()
        composeRule.onNodeWithTag(AdminTags.DASHBOARD).assertIsDisplayed()
    }

    @Test
    fun admin_canOpenFailedSyncs_thenARecord_andComeBackStepByStep() {
        launch(UserRole.ADMIN)

        composeRule.onNodeWithTag(AdminTags.entry(AdminDestination.FAILED_SYNC)).performScrollTo().performClick()
        composeRule.onNodeWithTag(FAILED_LIST_SLOT).assertIsDisplayed().performClick()
        composeRule.onNodeWithTag(FAILED_RECORD_SLOT).assertIsDisplayed()
        composeRule.runOnUiThread {
            assertEquals("record-1", navController.currentBackStackEntry?.arguments?.getString("id"))
            assertEquals("VETTING_DECISION", navController.currentBackStackEntry?.arguments?.getString("form"))
        }

        pressBack()
        composeRule.onNodeWithTag(FAILED_LIST_SLOT).assertIsDisplayed()
        pressBack()
        composeRule.onNodeWithTag(AdminTags.DASHBOARD).assertIsDisplayed()
    }

    @Test
    fun admin_graphDoesNotContainOtherRolesGraphs() {
        launch(UserRole.ADMIN)

        composeRule.onNodeWithTag(AdminTags.DASHBOARD).assertIsDisplayed()
        assertOnlyReachable(routesOf(Routes.ADMIN_GRAPH, *adminRoutes.toTypedArray()))
    }

    @Test
    fun adminSections_areUnreachableForCboCollection() = assertAdminRoutesUnreachableFor(UserRole.CBO_COLLECTION)

    @Test
    fun adminSections_areUnreachableForVetting() = assertAdminRoutesUnreachableFor(UserRole.VETTING)

    private fun assertAdminRoutesUnreachableFor(role: UserRole) {
        launch(role)
        composeRule.waitForIdle()
        composeRule.runOnUiThread {
            adminRoutes.forEach { route ->
                assertThrows("$role must not reach $route", IllegalArgumentException::class.java) { navController.navigate(route) }
            }
        }
    }

    fun cboCollection_startDestinationHasNothingBehindIt() =
        assertNothingBehindStartDestination(UserRole.CBO_COLLECTION)

    @Test
    fun vetting_startDestinationHasNothingBehindIt() =
        assertNothingBehindStartDestination(UserRole.VETTING)

    @Test
    fun admin_startDestinationHasNothingBehindIt() =
        assertNothingBehindStartDestination(UserRole.ADMIN)

    private fun assertNothingBehindStartDestination(role: UserRole) {
        launch(role)
        composeRule.waitForIdle()
        composeRule.runOnUiThread {
            assertEquals(role.startDestination(), navController.currentDestination?.route)
            // Back from the start destination leaves the app; no other role's screen is underneath.
            assertNull(navController.previousBackStackEntry)
        }
    }

    @Test
    fun noRole_showsLoginInsteadOfAnyRoleGraph() {
        launch(null)

        composeRule.onNodeWithTag(LOGIN_SLOT).assertIsDisplayed()
    }

    private fun pressBack() {
        composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitForIdle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }
}
