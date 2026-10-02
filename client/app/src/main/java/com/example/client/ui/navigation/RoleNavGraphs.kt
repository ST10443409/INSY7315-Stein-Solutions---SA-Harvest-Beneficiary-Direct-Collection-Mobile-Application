package com.example.client.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import androidx.navigation.navigation
import com.example.client.data.local.entity.AttachmentKind
import com.example.client.ui.admin.FAILED_SYNC_FORM_ARG
import com.example.client.ui.admin.FAILED_SYNC_ID_ARG
import com.example.client.ui.cbo.DoneVariant
import com.example.client.ui.vetting.VETTING_RECORD_ARG

// One nested graph per role. A graph only declares the destinations that role may
// reach, so access control is visible by inspection rather than via runtime checks.
// Any future deep link must be declared inside the owning role's graph.

fun NavGraphBuilder.cboCollectionGraph(navController: NavController, screens: CboScreens) {
    navigation(startDestination = Routes.CBO_HOME, route = Routes.CBO_GRAPH) {
        composable(Routes.CBO_HOME) {
            screens.home(
                { navController.navigateToTab(Routes.CBO_FORM1) },
                { navController.navigateToTab(Routes.CBO_SUBMISSIONS) },
                { navController.navigateToTab(Routes.CBO_SYNC) }
            )
        }
        collectionDestinations(
            navController, screens,
            CollectionRoutes(Routes.CBO_GRAPH, Routes.CBO_FORM1, Routes.CBO_SIGN, Routes.CBO_PHOTOS, Routes.CBO_DONE),
            variant = DoneVariant.COLLECTOR,
            onBack = { navController.navigateToTab(Routes.CBO_HOME) },
            onDonePrimary = { navController.navigateToTab(Routes.CBO_SYNC) },
            onDoneSecondary = { navController.navigateToTab(Routes.CBO_HOME) }
        )
        composable(Routes.CBO_SUBMISSIONS) { screens.history() }
        composable(Routes.CBO_SYNC) { screens.sync() }
    }
}

fun NavGraphBuilder.vettingGraph(navController: NavController, screens: VettingScreens) {
    navigation(startDestination = Routes.VETTING_FORM2, route = Routes.VETTING_GRAPH) {
        form2Destinations(navController, screens, Routes.VETTING_FORM2, Routes.VETTING_RECORD, Routes.VETTING_DECISION)
        composable(Routes.VETTING_SYNC) { screens.sync() }
    }
}

fun NavGraphBuilder.adminGraph(
    navController: NavController,
    cbo: CboScreens,
    vetting: VettingScreens,
    admin: AdminScreens = AdminScreens()
) {
    navigation(startDestination = Routes.ADMIN_DASHBOARD, route = Routes.ADMIN_GRAPH) {
        composable(Routes.ADMIN_DASHBOARD) {
            admin.overview { destination -> navController.navigate(destination.route) }
        }
        collectionDestinations(
            navController, cbo,
            CollectionRoutes(Routes.ADMIN_GRAPH, Routes.ADMIN_FORM1, Routes.ADMIN_SIGN, Routes.ADMIN_PHOTOS, Routes.ADMIN_DONE),
            variant = DoneVariant.ADMIN,
            onBack = null,
            onDonePrimary = { navController.navigateToTab(Routes.ADMIN_FORM1) },
            onDoneSecondary = { navController.navigateToTab(Routes.ADMIN_DASHBOARD) }
        )
        form2Destinations(navController, vetting, Routes.ADMIN_FORM2, Routes.ADMIN_RECORD, Routes.ADMIN_DECISION)
        composable(Routes.ADMIN_REPORTS) { admin.reports() }
        composable(Routes.ADMIN_SYNC_MONITOR) { admin.syncMonitor { navController.popBackStack() } }
        composable(Routes.ADMIN_FAILED_SYNC) {
            admin.failedSyncList(
                { navController.popBackStack() },
                { form, id -> navController.navigate(Routes.ADMIN_FAILED_SYNC_RECORD.withFailedSyncRecord(form.name, id)) }
            )
        }
        composable(
            Routes.ADMIN_FAILED_SYNC_RECORD,
            arguments = listOf(
                navArgument(FAILED_SYNC_FORM_ARG) { type = NavType.StringType },
                navArgument(FAILED_SYNC_ID_ARG) { type = NavType.StringType }
            )
        ) { admin.failedSyncRecord { navController.popBackStack() } }
        composable(Routes.ADMIN_USER_ACTIVITY) { admin.userActivity { navController.popBackStack() } }
    }
}

/** The route names of one role's copy of the collection screens (form, signature pad, photos, receipt). */
class CollectionRoutes(
    val graph: String,
    val collect: String,
    val sign: String,
    val photos: String,
    val done: String
)

/**
 * The collection form and the screens that hang off it, under one role's route names. All four share one Form1ViewModel,
 * held by the role's graph: a signature drawn on the pad is on the form when the user comes back, and switching tabs
 * does not lose a half-filled form.
 */
private fun NavGraphBuilder.collectionDestinations(
    navController: NavController,
    screens: CboScreens,
    routes: CollectionRoutes,
    variant: DoneVariant,
    onBack: (() -> Unit)?,
    onDonePrimary: () -> Unit,
    onDoneSecondary: () -> Unit
) {
    composable(routes.collect) { entry ->
        WithGraphViewModels(navController, entry, routes.graph) {
            screens.collect(
                { kind -> navController.navigate(routes.sign.withSignatureKind(kind)) },
                { navController.navigate(routes.photos) },
                // The receipt replaces the form, so Back from it does not land on a form that has just been submitted.
                { navController.navigate(routes.done) { popUpTo(routes.collect) { inclusive = true } } },
                onBack
            )
        }
    }
    composable(routes.sign, arguments = listOf(navArgument(SIGNATURE_KIND_ARG) { type = NavType.StringType })) { entry ->
        val kind = entry.arguments?.getString(SIGNATURE_KIND_ARG)
            ?.let { name -> AttachmentKind.values().firstOrNull { it.name == name } }
            ?.takeIf { it == AttachmentKind.CBO_SIGNATURE } ?: AttachmentKind.DONOR_SIGNATURE
        WithGraphViewModels(navController, entry, routes.graph) {
            screens.sign(kind) { navController.popBackStack() }
        }
    }
    composable(routes.photos) { entry ->
        WithGraphViewModels(navController, entry, routes.graph) {
            screens.photos { navController.popBackStack() }
        }
    }
    composable(routes.done) { entry ->
        WithGraphViewModels(navController, entry, routes.graph) {
            screens.done(variant, onDonePrimary, onDoneSecondary)
        }
    }
}

const val SIGNATURE_KIND_ARG = "kind"

/**
 * Runs [content] with the role graph's own ViewModel store, so every screen of the collection flow that asks for a
 * ViewModel gets the same instance, however many screens deep the user is.
 */
@Composable
private fun WithGraphViewModels(
    navController: NavController,
    entry: NavBackStackEntry,
    graphRoute: String,
    content: @Composable () -> Unit
) {
    val graphEntry = remember(entry) { navController.getBackStackEntry(graphRoute) }
    CompositionLocalProvider(LocalViewModelStoreOwner provides graphEntry) { content() }
}

/**
 * The three Form 2 screens (record list, one record's details, decision capture) under one role's route names.
 * The Vetting and Admin graphs each get their own copies, so no route is shared between graphs.
 */
private fun NavGraphBuilder.form2Destinations(
    navController: NavController,
    screens: VettingScreens,
    listRoute: String,
    recordRoute: String,
    decisionRoute: String
) {
    val recordArg = listOf(navArgument(VETTING_RECORD_ARG) { type = NavType.StringType })

    composable(listRoute) {
        screens.list { id -> navController.navigate(recordRoute.withRecordId(id)) }
    }
    composable(recordRoute, arguments = recordArg) {
        screens.detail(
            { navController.popBackStack() },
            { id -> navController.navigate(decisionRoute.withRecordId(id)) }
        )
    }
    composable(decisionRoute, arguments = recordArg) {
        screens.decision { navController.popBackStack() }
    }
}
