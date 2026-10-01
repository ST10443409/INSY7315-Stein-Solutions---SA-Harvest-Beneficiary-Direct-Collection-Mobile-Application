package com.example.client.ui.admin

import androidx.annotation.StringRes
import com.example.client.R
import com.example.client.ui.navigation.Routes

/** Where the Admin dashboard groups its entries. */
enum class AdminGroup { WORKFLOWS, OVERSIGHT }

/**
 * Everything the Admin dashboard can open, in display order. This is the one definition: the dashboard renders it, the
 * Admin graph registers a route for each, and a test checks the two agree.
 */
enum class AdminDestination(
    val group: AdminGroup,
    val route: String,
    @StringRes val title: Int,
    @StringRes val description: Int
) {
    FORM1(AdminGroup.WORKFLOWS, Routes.ADMIN_FORM1, R.string.admin_form1_title, R.string.admin_form1_desc),
    FORM2(AdminGroup.WORKFLOWS, Routes.ADMIN_FORM2, R.string.admin_form2_title, R.string.admin_form2_desc),

    /** Sync status monitoring (#49). */
    SYNC_MONITOR(AdminGroup.OVERSIGHT, Routes.ADMIN_SYNC_MONITOR, R.string.admin_sync_title, R.string.admin_sync_desc),

    /** Failed-sync resolution (#50). */
    FAILED_SYNC(AdminGroup.OVERSIGHT, Routes.ADMIN_FAILED_SYNC, R.string.admin_failed_title, R.string.admin_failed_desc),

    /** User activity oversight (#51). */
    USER_ACTIVITY(AdminGroup.OVERSIGHT, Routes.ADMIN_USER_ACTIVITY, R.string.admin_activity_title, R.string.admin_activity_desc)
}
