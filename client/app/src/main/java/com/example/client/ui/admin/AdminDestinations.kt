package com.example.client.ui.admin

import androidx.annotation.StringRes
import com.example.client.R
import com.example.client.ui.navigation.Routes

/**
 * The oversight sections the Admin Overview opens, in display order. This is the one definition: the Overview renders it,
 * the Admin graph registers a route for each, and a test checks the two agree. (The two workflows and the reports are tabs
 * of the Admin bottom bar, see `bottomNavItems`.)
 */
enum class AdminDestination(
    val route: String,
    @StringRes val title: Int,
    @StringRes val description: Int
) {
    /** Sync status monitoring (#49). */
    SYNC_MONITOR(Routes.ADMIN_SYNC_MONITOR, R.string.admin_sync_title, R.string.admin_sync_desc),

    /** Failed-sync resolution (#50). */
    FAILED_SYNC(Routes.ADMIN_FAILED_SYNC, R.string.admin_failed_title, R.string.admin_failed_desc),

    /** User activity oversight (#51). */
    USER_ACTIVITY(Routes.ADMIN_USER_ACTIVITY, R.string.admin_activity_title, R.string.admin_activity_desc)
}
