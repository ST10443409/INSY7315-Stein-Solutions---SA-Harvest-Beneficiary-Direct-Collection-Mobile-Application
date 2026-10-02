package com.example.client.ui.navigation

import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import com.example.client.R
import com.example.client.auth.UserRole
import com.example.client.ui.components.BottomNavItem
import com.example.client.ui.theme.GlyphPaths

/**
 * The tabs of each role's bottom navigation bar (from the CBO Collector design demo). Every tab is a route in that
 * role's own graph, so a role's bar can only ever lead to its own screens.
 */
fun UserRole.bottomNavItems(): List<BottomNavItem> = when (this) {
    UserRole.CBO_COLLECTION -> listOf(
        BottomNavItem(Routes.CBO_HOME, R.string.nav_runs, GlyphPaths.NavHome),
        BottomNavItem(Routes.CBO_FORM1, R.string.nav_collect, GlyphPaths.NavCollect),
        BottomNavItem(Routes.CBO_SUBMISSIONS, R.string.nav_history, GlyphPaths.NavHistory),
        BottomNavItem(Routes.CBO_SYNC, R.string.nav_sync, GlyphPaths.NavSync)
    )
    UserRole.VETTING -> listOf(
        BottomNavItem(Routes.VETTING_FORM2, R.string.nav_records, GlyphPaths.NavClipboard),
        BottomNavItem(Routes.VETTING_SYNC, R.string.nav_sync, GlyphPaths.NavSync)
    )
    UserRole.ADMIN -> listOf(
        BottomNavItem(Routes.ADMIN_DASHBOARD, R.string.nav_overview, GlyphPaths.NavHome),
        BottomNavItem(Routes.ADMIN_FORM1, R.string.nav_collect, GlyphPaths.NavCollect),
        BottomNavItem(Routes.ADMIN_FORM2, R.string.nav_vetting, GlyphPaths.NavApprove),
        BottomNavItem(Routes.ADMIN_REPORTS, R.string.nav_reports, GlyphPaths.NavBars)
    )
}

/**
 * Switches to a tab the way a bottom bar should: back to the role's start screen first (so Back from any tab goes home,
 * not through every tab visited), keeping each tab's state, and never stacking the same tab twice.
 */
fun NavController.navigateToTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
