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
    const val CBO_SUBMISSIONS = "cbo_submissions"

    // Vetting graph
    const val VETTING_GRAPH = "vetting_graph"
    const val VETTING_FORM2 = "vetting_form2"
    const val VETTING_RECORD = "vetting_record/{id}"
    const val VETTING_DECISION = "vetting_decision/{id}"

    // Admin graph
    const val ADMIN_GRAPH = "admin_graph"
    const val ADMIN_DASHBOARD = "admin_dashboard"
    const val ADMIN_FORM1 = "admin_form1"
    const val ADMIN_FORM2 = "admin_form2"
    const val ADMIN_RECORD = "admin_record/{id}"
    const val ADMIN_DECISION = "admin_decision/{id}"
    const val ADMIN_SYNC_MONITOR = "admin_sync_monitor"
    const val ADMIN_FAILED_SYNC = "admin_failed_sync"
    const val ADMIN_USER_ACTIVITY = "admin_user_activity"
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

/** The concrete route for one record, from a route template such as [Routes.VETTING_RECORD]. The id is URL-encoded: Foodspace ids are not assumed to be URL-safe. */
fun String.withRecordId(id: String): String = replace("{id}", android.net.Uri.encode(id))
