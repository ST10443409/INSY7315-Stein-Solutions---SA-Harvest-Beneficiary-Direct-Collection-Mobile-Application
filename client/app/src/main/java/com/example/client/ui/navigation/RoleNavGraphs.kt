package com.example.client.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.navigation
import com.example.client.ui.placeholder.AdminDashboardPlaceholderScreen
import com.example.client.ui.placeholder.Form2PlaceholderScreen
import com.example.client.ui.placeholder.SyncMonitorPlaceholderScreen

// One nested graph per role. A graph only declares the destinations that role may
// reach, so access control is visible by inspection rather than via runtime checks.
// Any future deep link must be declared inside the owning role's graph.

fun NavGraphBuilder.cboCollectionGraph(navController: NavController, screens: CboScreens) {
    navigation(startDestination = Routes.CBO_FORM1, route = Routes.CBO_GRAPH) {
        composable(Routes.CBO_FORM1) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Always visible on the form: how many submissions are safe, waiting, or need attention.
                screens.syncBadge { navController.navigate(Routes.CBO_SUBMISSIONS) }
                Box(modifier = Modifier.weight(1f)) { screens.form1() }
            }
        }
        composable(Routes.CBO_SUBMISSIONS) { screens.mySubmissions { navController.popBackStack() } }
    }
}

fun NavGraphBuilder.vettingGraph() {
    navigation(startDestination = Routes.VETTING_FORM2, route = Routes.VETTING_GRAPH) {
        composable(Routes.VETTING_FORM2) { Form2PlaceholderScreen() }
    }
}

fun NavGraphBuilder.adminGraph(navController: NavController, form1: @Composable () -> Unit) {
    navigation(startDestination = Routes.ADMIN_DASHBOARD, route = Routes.ADMIN_GRAPH) {
        composable(Routes.ADMIN_DASHBOARD) {
            AdminDashboardPlaceholderScreen(
                onOpenForm1 = { navController.navigate(Routes.ADMIN_FORM1) },
                onOpenForm2 = { navController.navigate(Routes.ADMIN_FORM2) },
                onOpenSyncMonitor = { navController.navigate(Routes.ADMIN_SYNC_MONITOR) }
            )
        }
        composable(Routes.ADMIN_FORM1) { form1() }
        composable(Routes.ADMIN_FORM2) { Form2PlaceholderScreen() }
        composable(Routes.ADMIN_SYNC_MONITOR) { SyncMonitorPlaceholderScreen() }
    }
}
