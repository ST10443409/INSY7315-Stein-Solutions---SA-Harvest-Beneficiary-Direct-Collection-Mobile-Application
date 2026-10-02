package com.example.client.ui.navigation

import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
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
import com.example.client.data.local.entity.AttachmentKind
import com.example.client.data.repository.SyncForm
import com.example.client.ui.admin.AdminDestination
import com.example.client.ui.admin.AdminTags
import com.example.client.ui.components.bottomNavTag


import com.example.client.ui.placeholder.ScreenTags
import com.example.client.ui.splash.SplashTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Role isolation and the bottom navigation, on a device. The screens are plain stand-ins (a tag and a few buttons) so
 * what is under test is the navigation: which routes a role has, which tabs its bar shows, and where Back goes.
 */
@RunWith(AndroidJUnit4::class)
class RoleNavigationTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var navController: NavHostController

    private companion object {
        const val LOGIN_SLOT = "login_slot"
        const val HOME_SLOT = "home_slot"
        const val HISTORY_SLOT = "history_slot"
        const val CBO_SYNC_SLOT = "cbo_sync_slot"
        const val SIGN_SLOT = "sign_slot"
        const val PHOTOS_SLOT = "photos_slot"
        const val DONE_SLOT = "done_slot"
        const val VETTING_SYNC_SLOT = "vetting_sync_slot"
        const val REPORTS_SLOT = "reports_slot"
        const val SYNC_MONITOR_SLOT = "sync_monitor_slot"
        const val FAILED_LIST_SLOT = "failed_list_slot"
        const val FAILED_RECORD_SLOT = "failed_record_slot"
        const val USER_ACTIVITY_SLOT = "user_activity_slot"
        const val OPEN_SIGN = "open_sign"
        const val OPEN_CBO_SIGN = "open_cbo_sign"
        const val OPEN_PHOTOS = "open_photos"
        const val SUBMIT = "submit"
        const val OPEN_RECORD = "open_record"
        const val GO_HISTORY = "go_history"
        const val DONE_PRIMARY = "done_primary_slot"
        const val DONE_SECONDARY = "done_secondary_slot"
    }

    // The kinds of signature the pad was opened for, in order; recorded once per opening (an exiting screen recomposes).
    private val signKinds = mutableListOf<AttachmentKind>()
    private var lastDoneVariantIsAdmin: Boolean? = null

    private fun cboScreens() = CboScreens(
        home = { _, history, _ ->
            Column {
                Text("home", Modifier.testTag(HOME_SLOT))
                Text("history", Modifier.testTag(GO_HISTORY).clickable { history() })
            }
        },
        collect = { sign, photos, submitted, _ ->
            Column {
                Text("Form 1", Modifier.testTag(ScreenTags.FORM1))
                Text("sign donor", Modifier.testTag(OPEN_SIGN).clickable { sign(AttachmentKind.DONOR_SIGNATURE) })
                Text("sign cbo", Modifier.testTag(OPEN_CBO_SIGN).clickable { sign(AttachmentKind.CBO_SIGNATURE) })
                Text("photos", Modifier.testTag(OPEN_PHOTOS).clickable { photos() })
                Text("submit", Modifier.testTag(SUBMIT).clickable { submitted() })
            }
        },
        sign = { kind, _ ->
            androidx.compose.runtime.LaunchedEffect(Unit) { signKinds += kind }
            Text("sign", Modifier.testTag(SIGN_SLOT))
        },
        photos = { Text("photos", Modifier.testTag(PHOTOS_SLOT)) },
        done = { variant, primary, secondary ->
            lastDoneVariantIsAdmin = variant == com.example.client.ui.cbo.DoneVariant.ADMIN
            Column {
                Text("done", Modifier.testTag(DONE_SLOT))
                Text("primary", Modifier.testTag(DONE_PRIMARY).clickable { primary() })
                Text("secondary", Modifier.testTag(DONE_SECONDARY).clickable { secondary() })
            }
        },
        history = { Text("history", Modifier.testTag(HISTORY_SLOT)) },
        sync = { Text("sync", Modifier.testTag(CBO_SYNC_SLOT)) }
    )

    private fun vettingScreens() = VettingScreens(
        list = { onOpen -> Column { Text("Form 2", Modifier.testTag(ScreenTags.FORM2)); Text("open", Modifier.testTag(OPEN_RECORD).clickable { onOpen("rec-1") }) } },
        detail = { _, _ -> },
        decision = { },
        sync = { Text("vetting sync", Modifier.testTag(VETTING_SYNC_SLOT)) }
    )

    private fun adminScreens() = AdminScreens(
        overview = { onOpen ->
            Column {
                Text("overview", Modifier.testTag(AdminTags.DASHBOARD))
                AdminDestination.values().forEach { d -> Text(d.name, Modifier.testTag(AdminTags.entry(d)).clickable { onOpen(d) }) }
            }
        },
        reports = { Text("reports", Modifier.testTag(REPORTS_SLOT)) },
        syncMonitor = { Text("sync monitor", Modifier.testTag(SYNC_MONITOR_SLOT)) },
        failedSyncList = { _, onOpen ->
            Text("failed syncs", Modifier.testTag(FAILED_LIST_SLOT).clickable { onOpen(SyncForm.VETTING_DECISION, "record-1") })
        },
        failedSyncRecord = { Text("failed sync record", Modifier.testTag(FAILED_RECORD_SLOT)) },
        userActivity = { Text("user activity", Modifier.testTag(USER_ACTIVITY_SLOT)) }
    )

    private fun launch(role: UserRole?) {
        composeRule.setContent {
            navController = rememberNavController()
            if (role == null) {
                AppRoot(null, login = { Text("login", Modifier.testTag(LOGIN_SLOT)) })
            } else {
                AppNavHost(role, navController, screens = cboScreens(), vetting = vettingScreens(), admin = adminScreens())
            }
        }
    }

    private val cboRoutes = listOf(
        Routes.CBO_GRAPH, Routes.CBO_HOME, Routes.CBO_FORM1, Routes.CBO_SIGN, Routes.CBO_PHOTOS, Routes.CBO_DONE,
        Routes.CBO_SUBMISSIONS, Routes.CBO_SYNC
    )

    private val vettingRoutes = listOf(
        Routes.VETTING_GRAPH, Routes.VETTING_FORM2, Routes.VETTING_SYNC, Routes.VETTING_RECORD, Routes.VETTING_DECISION
    )

    private val adminRoutes = listOf(
        Routes.ADMIN_DASHBOARD, Routes.ADMIN_FORM1, Routes.ADMIN_SIGN, Routes.ADMIN_PHOTOS, Routes.ADMIN_DONE, Routes.ADMIN_FORM2,
        Routes.ADMIN_RECORD, Routes.ADMIN_DECISION, Routes.ADMIN_REPORTS, Routes.ADMIN_SYNC_MONITOR, Routes.ADMIN_FAILED_SYNC,
        Routes.ADMIN_FAILED_SYNC_RECORD, Routes.ADMIN_USER_ACTIVITY
    )

    // NavGraph.findNode only checks direct children, so walk nested graphs explicitly.
    private fun allDestinations(destination: NavDestination): List<NavDestination> =
        if (destination is NavGraph) listOf(destination) + destination.flatMap { allDestinations(it) }
        else listOf(destination)

    private fun assertOnlyReachable(allowed: List<String>) {
        val everyRoute = cboRoutes + vettingRoutes + adminRoutes + Routes.ADMIN_GRAPH
        composeRule.runOnUiThread {
            everyRoute.forEach { route ->
                val node = allDestinations(navController.graph).firstOrNull { it.route == route }
                if (route in allowed) assertNotNull("$route should exist", node)
                else assertNull("$route must not exist in this role's graph", node)
            }
        }
    }

    private fun currentRoute() = navController.currentDestination?.route

    private fun tab(route: String) = composeRule.onNodeWithTag(bottomNavTag(route))

    // ── CBO collection ──────────────────────────────────────────────────────────────

    @Test
    fun cboCollection_landsOnHome_withItsFourTabs_andHasNoRouteToOtherRoles() {
        launch(UserRole.CBO_COLLECTION)

        composeRule.onNodeWithTag(HOME_SLOT).assertIsDisplayed()
        listOf(Routes.CBO_HOME, Routes.CBO_FORM1, Routes.CBO_SUBMISSIONS, Routes.CBO_SYNC).forEach { tab(it).assertIsDisplayed() }
        tab(Routes.CBO_HOME).assertIsSelected()
        assertOnlyReachable(cboRoutes)
        composeRule.runOnUiThread {
            assertEquals(Routes.CBO_HOME, currentRoute())
            assertThrows(IllegalArgumentException::class.java) { navController.navigate(Routes.VETTING_FORM2) }
            assertThrows(IllegalArgumentException::class.java) { navController.navigate(Routes.ADMIN_DASHBOARD) }
        }
    }

    @Test
    fun cboCollection_tabsSwitchScreens_andBackReturnsHome() {
        launch(UserRole.CBO_COLLECTION)

        tab(Routes.CBO_FORM1).performClick()
        composeRule.onNodeWithTag(ScreenTags.FORM1).assertIsDisplayed()
        tab(Routes.CBO_FORM1).assertIsSelected()

        tab(Routes.CBO_SUBMISSIONS).performClick()
        composeRule.onNodeWithTag(HISTORY_SLOT).assertIsDisplayed()

        tab(Routes.CBO_SYNC).performClick()
        composeRule.onNodeWithTag(CBO_SYNC_SLOT).assertIsDisplayed()

        // Back from any tab goes straight home, not back through every tab visited.
        pressBack()
        composeRule.onNodeWithTag(HOME_SLOT).assertIsDisplayed()
        composeRule.runOnUiThread { assertEquals(Routes.CBO_HOME, currentRoute()) }
    }

    @Test
    fun cboCollection_homeLinksOpenTheirTabs() {
        launch(UserRole.CBO_COLLECTION)

        composeRule.onNodeWithTag(GO_HISTORY).performClick()

        composeRule.onNodeWithTag(HISTORY_SLOT).assertIsDisplayed()
        tab(Routes.CBO_SUBMISSIONS).assertIsSelected()
    }

    @Test
    fun cboCollection_signaturePadOpensForTheRightSignature_andBackReturnsToTheForm() {
        launch(UserRole.CBO_COLLECTION)
        tab(Routes.CBO_FORM1).performClick()

        composeRule.onNodeWithTag(OPEN_SIGN).performClick()
        composeRule.onNodeWithTag(SIGN_SLOT).assertIsDisplayed()
        assertEquals(listOf(AttachmentKind.DONOR_SIGNATURE), signKinds)
        composeRule.onNodeWithTag(bottomNavTag(Routes.CBO_HOME)).assertDoesNotExist() // no tab bar on the pad

        pressBack()
        composeRule.onNodeWithTag(ScreenTags.FORM1).assertIsDisplayed()

        composeRule.onNodeWithTag(OPEN_CBO_SIGN).performClick()
        composeRule.waitForIdle()
        assertEquals(listOf(AttachmentKind.DONOR_SIGNATURE, AttachmentKind.CBO_SIGNATURE), signKinds)
    }

    @Test
    fun cboCollection_photosOpensFromTheForm_andBackReturnsToIt() {
        launch(UserRole.CBO_COLLECTION)
        tab(Routes.CBO_FORM1).performClick()

        composeRule.onNodeWithTag(OPEN_PHOTOS).performClick()
        composeRule.onNodeWithTag(PHOTOS_SLOT).assertIsDisplayed()

        pressBack()
        composeRule.onNodeWithTag(ScreenTags.FORM1).assertIsDisplayed()
    }

    @Test
    fun cboCollection_aSubmittedForm_showsTheReceipt_whichBackDoesNotUndo() {
        launch(UserRole.CBO_COLLECTION)
        tab(Routes.CBO_FORM1).performClick()

        composeRule.onNodeWithTag(SUBMIT).performClick()
        composeRule.onNodeWithTag(DONE_SLOT).assertIsDisplayed()
        assertEquals(false, lastDoneVariantIsAdmin)

        // The form that was just submitted is replaced by the receipt, so Back leaves it rather than reopening it.
        pressBack()
        composeRule.onNodeWithTag(HOME_SLOT).assertIsDisplayed()
    }

    @Test
    fun cboCollection_theReceiptsButtons_goToTheSyncTabAndHome() {
        launch(UserRole.CBO_COLLECTION)
        tab(Routes.CBO_FORM1).performClick()
        composeRule.onNodeWithTag(SUBMIT).performClick()

        composeRule.onNodeWithTag(DONE_PRIMARY).performClick()
        composeRule.onNodeWithTag(CBO_SYNC_SLOT).assertIsDisplayed()

        tab(Routes.CBO_FORM1).performClick()
        composeRule.onNodeWithTag(SUBMIT).performClick()
        composeRule.onNodeWithTag(DONE_SECONDARY).performClick()
        composeRule.onNodeWithTag(HOME_SLOT).assertIsDisplayed()
    }

    /** Stands in for Form1ViewModel: any ViewModel the collection screens ask for. */
    class FlowViewModel : androidx.lifecycle.ViewModel()

    @Test
    fun cboCollection_theFormAndItsScreens_shareOneViewModel_thatTabSwitchesDoNotLose() {
        val seen = mutableMapOf<String, FlowViewModel>()
        composeRule.setContent {
            navController = rememberNavController()
            AppNavHost(
                UserRole.CBO_COLLECTION, navController,
                screens = CboScreens(
                    collect = { sign, photos, submitted, _ ->
                        seen["collect"] = androidx.lifecycle.viewmodel.compose.viewModel()
                        Column {
                            Text("Form 1", Modifier.testTag(ScreenTags.FORM1))
                            Text("sign", Modifier.testTag(OPEN_SIGN).clickable { sign(AttachmentKind.DONOR_SIGNATURE) })
                            Text("photos", Modifier.testTag(OPEN_PHOTOS).clickable { photos() })
                            Text("submit", Modifier.testTag(SUBMIT).clickable { submitted() })
                        }
                    },
                    sign = { _, _ -> seen["sign"] = androidx.lifecycle.viewmodel.compose.viewModel(); Text("sign", Modifier.testTag(SIGN_SLOT)) },
                    photos = { seen["photos"] = androidx.lifecycle.viewmodel.compose.viewModel(); Text("photos", Modifier.testTag(PHOTOS_SLOT)) },
                    done = { _, _, _ -> seen["done"] = androidx.lifecycle.viewmodel.compose.viewModel(); Text("done", Modifier.testTag(DONE_SLOT)) },
                    home = { _, _, _ -> Text("home", Modifier.testTag(HOME_SLOT)) },
                    history = { Text("history", Modifier.testTag(HISTORY_SLOT)) }
                )
            )
        }
        tab(Routes.CBO_FORM1).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(ScreenTags.FORM1).assertIsDisplayed()
        val form = seen.getValue("collect")

        // Each screen is given a moment to compose before the next step, as a user would wait for it.
        fun awaitScreen(name: String, slot: String) {
            composeRule.waitUntil(5_000) { seen.containsKey(name) }
            composeRule.onNodeWithTag(slot).assertIsDisplayed()
        }
        composeRule.onNodeWithTag(OPEN_SIGN).performClick()
        awaitScreen("sign", SIGN_SLOT)
        pressBack()
        composeRule.onNodeWithTag(OPEN_PHOTOS).performClick()
        awaitScreen("photos", PHOTOS_SLOT)
        pressBack()
        // Leaving the form for another tab and coming back must not throw the half-filled form away.
        tab(Routes.CBO_SUBMISSIONS).performClick()
        composeRule.onNodeWithTag(HISTORY_SLOT).assertIsDisplayed()
        tab(Routes.CBO_FORM1).performClick()
        composeRule.onNodeWithTag(SUBMIT).performClick()
        awaitScreen("done", DONE_SLOT)

        listOf("sign", "photos", "done").forEach { name ->
            org.junit.Assert.assertSame("$name must share the form's ViewModel", form, seen.getValue(name))
        }
        org.junit.Assert.assertSame(form, seen.getValue("collect"))
    }

    // ── Vetting ─────────────────────────────────────────────────────────────────────

    @Test
    fun vetting_landsOnRecords_withTwoTabs_andHasNoRouteToOtherRoles() {
        launch(UserRole.VETTING)

        composeRule.onNodeWithTag(ScreenTags.FORM2).assertIsDisplayed()
        tab(Routes.VETTING_FORM2).assertIsSelected()
        tab(Routes.VETTING_SYNC).assertIsDisplayed()
        assertOnlyReachable(vettingRoutes)
        composeRule.runOnUiThread {
            assertEquals(Routes.VETTING_FORM2, currentRoute())
            assertThrows(IllegalArgumentException::class.java) { navController.navigate(Routes.CBO_FORM1) }
            assertThrows(IllegalArgumentException::class.java) { navController.navigate(Routes.ADMIN_DASHBOARD) }
        }
    }

    @Test
    fun vetting_syncTabOpens_andBackReturnsToTheRecords() {
        launch(UserRole.VETTING)

        tab(Routes.VETTING_SYNC).performClick()
        composeRule.onNodeWithTag(VETTING_SYNC_SLOT).assertIsDisplayed()

        pressBack()
        composeRule.onNodeWithTag(ScreenTags.FORM2).assertIsDisplayed()
    }

    @Test
    fun vetting_aRecordsDetail_hasNoTabBar() {
        launch(UserRole.VETTING)

        composeRule.onNodeWithTag(OPEN_RECORD).performClick()
        composeRule.waitForIdle()

        composeRule.runOnUiThread { assertEquals(Routes.VETTING_RECORD, currentRoute()) }
        composeRule.onNodeWithTag(bottomNavTag(Routes.VETTING_FORM2)).assertDoesNotExist()
        pressBack()
        tab(Routes.VETTING_FORM2).assertIsDisplayed()
    }

    // ── Admin ───────────────────────────────────────────────────────────────────────

    @Test
    fun admin_landsOnTheOverview_withItsFourTabs_andAnEntryForEveryOversightSection() {
        launch(UserRole.ADMIN)

        composeRule.onNodeWithTag(AdminTags.DASHBOARD).assertIsDisplayed()
        listOf(Routes.ADMIN_DASHBOARD, Routes.ADMIN_FORM1, Routes.ADMIN_FORM2, Routes.ADMIN_REPORTS).forEach { tab(it).assertIsDisplayed() }
        AdminDestination.values().forEach { composeRule.onNodeWithTag(AdminTags.entry(it)).assertIsDisplayed() }
    }

    @Test
    fun admin_tabsOpenTheCollectionForm_theVettingList_andTheReports() {
        launch(UserRole.ADMIN)

        tab(Routes.ADMIN_FORM1).performClick()
        composeRule.onNodeWithTag(ScreenTags.FORM1).assertIsDisplayed()

        tab(Routes.ADMIN_FORM2).performClick()
        composeRule.onNodeWithTag(ScreenTags.FORM2).assertIsDisplayed()

        tab(Routes.ADMIN_REPORTS).performClick()
        composeRule.onNodeWithTag(REPORTS_SLOT).assertIsDisplayed()

        pressBack()
        composeRule.onNodeWithTag(AdminTags.DASHBOARD).assertIsDisplayed()
    }

    @Test
    fun admin_theCollectionFlow_isTheSameScreensUnderTheAdminsOwnRoutes() {
        launch(UserRole.ADMIN)
        tab(Routes.ADMIN_FORM1).performClick()

        composeRule.onNodeWithTag(OPEN_SIGN).performClick()
        composeRule.onNodeWithTag(SIGN_SLOT).assertIsDisplayed()
        composeRule.runOnUiThread { assertEquals(Routes.ADMIN_SIGN, currentRoute()) }
        pressBack()

        composeRule.onNodeWithTag(OPEN_PHOTOS).performClick()
        composeRule.onNodeWithTag(PHOTOS_SLOT).assertIsDisplayed()
        pressBack()

        composeRule.onNodeWithTag(SUBMIT).performClick()
        composeRule.onNodeWithTag(DONE_SLOT).assertIsDisplayed()
        assertEquals(true, lastDoneVariantIsAdmin)

        // An Admin has no sync tab: the receipt's buttons start another collection, or go back to the overview.
        composeRule.onNodeWithTag(DONE_SECONDARY).performClick()
        composeRule.onNodeWithTag(AdminTags.DASHBOARD).assertIsDisplayed()
    }

    @Test
    fun admin_canOpenUserActivity_andComeBack() {
        launch(UserRole.ADMIN)

        composeRule.onNodeWithTag(AdminTags.entry(AdminDestination.USER_ACTIVITY)).performClick()
        composeRule.onNodeWithTag(USER_ACTIVITY_SLOT).assertIsDisplayed()
        composeRule.onNodeWithTag(bottomNavTag(Routes.ADMIN_DASHBOARD)).assertDoesNotExist()
        pressBack()
        composeRule.onNodeWithTag(AdminTags.DASHBOARD).assertIsDisplayed()
    }

    @Test
    fun admin_canOpenTheSyncMonitor_andComeBack() {
        launch(UserRole.ADMIN)

        composeRule.onNodeWithTag(AdminTags.entry(AdminDestination.SYNC_MONITOR)).performClick()
        composeRule.onNodeWithTag(SYNC_MONITOR_SLOT).assertIsDisplayed()
        pressBack()
        composeRule.onNodeWithTag(AdminTags.DASHBOARD).assertIsDisplayed()
    }

    @Test
    fun admin_canOpenFailedSyncs_thenARecord_andComeBackStepByStep() {
        launch(UserRole.ADMIN)

        composeRule.onNodeWithTag(AdminTags.entry(AdminDestination.FAILED_SYNC)).performClick()
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
        assertOnlyReachable(listOf(Routes.ADMIN_GRAPH) + adminRoutes)
    }

    // ── Isolation and start destinations ────────────────────────────────────────────

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
    fun everyTabOfEveryRole_isARouteInThatRolesOwnGraph() {
        UserRole.values().forEach { role ->
            val tabs = role.bottomNavItems().map { it.route }
            val own = when (role) {
                UserRole.CBO_COLLECTION -> cboRoutes
                UserRole.VETTING -> vettingRoutes
                UserRole.ADMIN -> adminRoutes + Routes.ADMIN_GRAPH
            }
            assertEquals("$role has a tab that is not in its own graph", tabs, tabs.filter { it in own })
            assertEquals("the first tab is where the role lands", role.startDestination(), tabs.first())
        }
    }

    // ── Signed out ──────────────────────────────────────────────────────────────────

    @Test
    fun noRole_showsTheSplashFirst_thenTheLogin() {
        launch(null)

        composeRule.onNodeWithTag(SplashTags.SCREEN).assertIsDisplayed()
        composeRule.onNodeWithTag(LOGIN_SLOT).assertDoesNotExist()

        composeRule.onNodeWithTag(SplashTags.CONTINUE).performClick()

        composeRule.onNodeWithTag(LOGIN_SLOT).assertIsDisplayed()
        composeRule.onNodeWithTag(SplashTags.SCREEN).assertDoesNotExist()
    }

    private fun pressBack() {
        composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitForIdle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }
}
