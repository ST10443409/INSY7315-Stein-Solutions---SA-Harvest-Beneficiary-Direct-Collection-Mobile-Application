package com.example.client.ui.navigation

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.navigation
import com.example.client.ui.placeholder.AdminDashboardPlaceholderScreen
import com.example.client.ui.placeholder.Form1PlaceholderScreen
import com.example.client.ui.placeholder.Form2PlaceholderScreen
import com.example.client.ui.placeholder.SyncMonitorPlaceholderScreen

// One nested graph per role. A graph only declares the destinations that role may
// reach, so access control is visible by inspection rather than via runtime checks.
// Any future deep link must be declared inside the owning role's graph.

fun NavGraphBuilder.cboCollectionGraph() {
    navigation(startDestination = Routes.CBO_FORM1, route = Routes.CBO_GRAPH) {
        composable(Routes.CBO_FORM1) { Form1PlaceholderScreen() }
    }
}

fun NavGraphBuilder.vettingGraph() {
    navigation(startDestination = Routes.VETTING_FORM2, route = Routes.VETTING_GRAPH) {
        composable(Routes.VETTING_FORM2) { Form2PlaceholderScreen() }
    }
}

fun NavGraphBuilder.adminGraph(navController: NavController) {
    navigation(startDestination = Routes.ADMIN_DASHBOARD, route = Routes.ADMIN_GRAPH) {
        composable(Routes.ADMIN_DASHBOARD) {
            AdminDashboardPlaceholderScreen(
                onOpenForm1 = { navController.navigate(Routes.ADMIN_FORM1) },
                onOpenForm2 = { navController.navigate(Routes.ADMIN_FORM2) },
                onOpenSyncMonitor = { navController.navigate(Routes.ADMIN_SYNC_MONITOR) }
            )
        }
        composable(Routes.ADMIN_FORM1) { Form1PlaceholderScreen() }
        composable(Routes.ADMIN_FORM2) { Form2PlaceholderScreen() }
        composable(Routes.ADMIN_SYNC_MONITOR) { SyncMonitorPlaceholderScreen() }
    }
}
