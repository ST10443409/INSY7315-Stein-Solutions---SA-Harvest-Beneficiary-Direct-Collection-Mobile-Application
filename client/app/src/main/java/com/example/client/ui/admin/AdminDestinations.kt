package com.example.client.ui.admin

import androidx.annotation.StringRes
import com.example.client.R
import com.example.client.ui.navigation.Routes

/** Where the Admin dashboard groups its entries. */
enum class AdminGroup { WORKFLOWS, OVERSIGHT }

/**
 * Everything the Admin dashboard can open, in display order. This is the one definition: the dashboard renders it, the
 * Admin graph registers a route for each, and a test checks the two agree. [available] is false for a section whose
 * screen has not been built yet (it shows a labelled "coming soon" screen instead).
 */
enum class AdminDestination(
    val group: AdminGroup,
    val route: String,
    @StringRes val title: Int,
    @StringRes val description: Int,
    val available: Boolean
) {
    FORM1(AdminGroup.WORKFLOWS, Routes.ADMIN_FORM1, R.string.admin_form1_title, R.string.admin_form1_desc, available = true),
    FORM2(AdminGroup.WORKFLOWS, Routes.ADMIN_FORM2, R.string.admin_form2_title, R.string.admin_form2_desc, available = true),

    /** Sync status monitoring (#49). */
    SYNC_MONITOR(AdminGroup.OVERSIGHT, Routes.ADMIN_SYNC_MONITOR, R.string.admin_sync_title, R.string.admin_sync_desc, available = true),

    /** Failed-sync resolution (#50). */
    FAILED_SYNC(AdminGroup.OVERSIGHT, Routes.ADMIN_FAILED_SYNC, R.string.admin_failed_title, R.string.admin_failed_desc, available = false),

    /** User activity oversight (#51). */
    USER_ACTIVITY(AdminGroup.OVERSIGHT, Routes.ADMIN_USER_ACTIVITY, R.string.admin_activity_title, R.string.admin_activity_desc, available = false);

    companion object {
        /** The destinations whose screen is only a placeholder so far. */
        val placeholders: List<AdminDestination> get() = values().filterNot { it.available }
    }
}
