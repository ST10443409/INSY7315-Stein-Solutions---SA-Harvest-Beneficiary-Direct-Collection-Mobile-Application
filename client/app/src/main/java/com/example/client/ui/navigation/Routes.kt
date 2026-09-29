package com.example.client.ui.navigation

import com.example.client.auth.UserRole

/**
 * Route names. Every role-specific destination is namespaced by its graph so no route
 * is shared between graphs, and only the current role's graph is ever registered.
 */
object Routes {
    // CBO Collection graph
    const val CBO_GRAPH = "cbo_graph"
    const val CBO_FORM1 = "cbo_form1"

    // Vetting graph
    const val VETTING_GRAPH = "vetting_graph"
    const val VETTING_FORM2 = "vetting_form2"

    // Admin graph
    const val ADMIN_GRAPH = "admin_graph"
    const val ADMIN_DASHBOARD = "admin_dashboard"
    const val ADMIN_FORM1 = "admin_form1"
    const val ADMIN_FORM2 = "admin_form2"
    const val ADMIN_SYNC_MONITOR = "admin_sync_monitor"
}

/** Route of the nested graph that belongs to this role. */
fun UserRole.graphRoute(): String = when (this) {
    UserRole.CBO_COLLECTION -> Routes.CBO_GRAPH
    UserRole.VETTING -> Routes.VETTING_GRAPH
    UserRole.ADMIN -> Routes.ADMIN_GRAPH
}

/** The screen a role lands on when the app opens. */
fun UserRole.startDestination(): String = when (this) {
    UserRole.CBO_COLLECTION -> Routes.CBO_FORM1
    UserRole.VETTING -> Routes.VETTING_FORM2
    UserRole.ADMIN -> Routes.ADMIN_DASHBOARD
}
