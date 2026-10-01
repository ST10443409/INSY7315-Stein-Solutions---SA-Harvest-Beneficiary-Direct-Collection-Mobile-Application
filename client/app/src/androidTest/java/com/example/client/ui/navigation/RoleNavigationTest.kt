package com.example.client.ui.navigation

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.navigation.NavDestination
import androidx.navigation.NavGraph
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.client.auth.UserRole
import com.example.client.ui.placeholder.Form1PlaceholderScreen
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
    }

    private fun launch(role: UserRole?) {
        composeRule.setContent {
            navController = rememberNavController()
            if (role == null) AppRoot(null, login = { Text("login", Modifier.testTag(LOGIN_SLOT)) }) else AppNavHost(
                role, navController,
                screens = CboScreens(form1 = { Form1PlaceholderScreen() }, syncBadge = {}, mySubmissions = {})
            )
        }
    }

    private fun routesOf(vararg routes: String) = routes.toList()

    // NavGraph.findNode only checks direct children, so walk nested graphs explicitly.
    private fun allDestinations(destination: NavDestination): List<NavDestination> =
        if (destination is NavGraph) listOf(destination) + destination.flatMap { allDestinations(it) }
        else listOf(destination)

    private fun assertOnlyReachable(allowed: List<String>) {
        val everyRoute = listOf(
            Routes.CBO_GRAPH, Routes.CBO_FORM1, Routes.CBO_SUBMISSIONS,
            Routes.VETTING_GRAPH, Routes.VETTING_FORM2,
            Routes.ADMIN_GRAPH, Routes.ADMIN_DASHBOARD, Routes.ADMIN_FORM1,
            Routes.ADMIN_FORM2, Routes.ADMIN_SYNC_MONITOR
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
        assertOnlyReachable(routesOf(Routes.VETTING_GRAPH, Routes.VETTING_FORM2))
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
    fun admin_landsOnDashboard_withEntryPointsToBothFormsAndSyncMonitor() {
        launch(UserRole.ADMIN)

        composeRule.onNodeWithTag(ScreenTags.ADMIN_DASHBOARD).assertIsDisplayed()
        composeRule.onNodeWithTag(ScreenTags.OPEN_FORM1).assertIsDisplayed()
        composeRule.onNodeWithTag(ScreenTags.OPEN_FORM2).assertIsDisplayed()
        composeRule.onNodeWithTag(ScreenTags.OPEN_SYNC_MONITOR).assertIsDisplayed()

        composeRule.onNodeWithTag(ScreenTags.OPEN_FORM1).performClick()
        composeRule.onNodeWithTag(ScreenTags.FORM1).assertIsDisplayed()
        pressBack()
        composeRule.onNodeWithTag(ScreenTags.ADMIN_DASHBOARD).assertIsDisplayed()

        composeRule.onNodeWithTag(ScreenTags.OPEN_FORM2).performClick()
        composeRule.onNodeWithTag(ScreenTags.FORM2).assertIsDisplayed()
        pressBack()

        composeRule.onNodeWithTag(ScreenTags.OPEN_SYNC_MONITOR).performClick()
        composeRule.onNodeWithTag(ScreenTags.SYNC_MONITOR).assertIsDisplayed()
    }

    @Test
    fun admin_graphDoesNotContainOtherRolesGraphs() {
        launch(UserRole.ADMIN)

        composeRule.onNodeWithTag(ScreenTags.ADMIN_DASHBOARD).assertIsDisplayed()
        assertOnlyReachable(
            routesOf(
                Routes.ADMIN_GRAPH, Routes.ADMIN_DASHBOARD, Routes.ADMIN_FORM1,
                Routes.ADMIN_FORM2, Routes.ADMIN_SYNC_MONITOR
            )
        )
    }

    @Test
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
