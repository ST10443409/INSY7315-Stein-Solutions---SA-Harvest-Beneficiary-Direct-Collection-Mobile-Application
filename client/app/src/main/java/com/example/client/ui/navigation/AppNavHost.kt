package com.example.client.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import com.example.client.auth.UserRole
import com.example.client.ui.cbo.Form1Route

/**
 * Root of the navigation shell. Shows [login] while there is no role (signed out, or the
 * session expired) and re-creates the role's graph from scratch whenever the role changes,
 * so a previous role's back stack can never leak into the next one.
 */
@Composable
fun AppRoot(
    role: UserRole?,
    form1: @Composable () -> Unit = { Form1Route() },
    login: @Composable () -> Unit
) {
    if (role == null) {
        login()
    } else {
        key(role) { AppNavHost(role, form1 = form1) }
    }
}

/**
 * Registers only [role]'s nested graph. Routes belonging to other roles do not exist
 * in this NavHost, so they cannot be reached by back navigation or by navigating to them.
 */
@Composable
fun AppNavHost(
    role: UserRole,
    navController: NavHostController = rememberNavController(),
    form1: @Composable () -> Unit = { Form1Route() }
) {
    NavHost(navController = navController, startDestination = role.graphRoute()) {
        when (role) {
            UserRole.CBO_COLLECTION -> cboCollectionGraph(form1)
            UserRole.VETTING -> vettingGraph()
            UserRole.ADMIN -> adminGraph(navController, form1)
        }
    }
}
