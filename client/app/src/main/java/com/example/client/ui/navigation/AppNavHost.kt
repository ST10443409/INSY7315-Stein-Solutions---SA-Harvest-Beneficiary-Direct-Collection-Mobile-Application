package com.example.client.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.client.auth.UserRole
import com.example.client.data.local.entity.AttachmentKind
import com.example.client.data.repository.SyncForm
import com.example.client.ui.admin.AdminDestination
import com.example.client.ui.admin.AdminOverviewRoute
import com.example.client.ui.admin.FailedSyncDetailRoute
import com.example.client.ui.admin.FailedSyncListRoute
import com.example.client.ui.admin.ReportsRoute
import com.example.client.ui.admin.SyncMonitorRoute
import com.example.client.ui.admin.UserActivityRoute
import com.example.client.ui.cbo.CboHomeRoute
import com.example.client.ui.cbo.CboSyncRoute
import com.example.client.ui.cbo.CollectionDoneRoute
import com.example.client.ui.cbo.DoneVariant
import com.example.client.ui.cbo.Form1Route
import com.example.client.ui.cbo.HistoryRoute
import com.example.client.ui.cbo.PhotosRoute
import com.example.client.ui.cbo.SignatureRoute
import com.example.client.ui.components.BottomNavBar
import com.example.client.ui.splash.SplashScreen
import com.example.client.ui.theme.SaColors
import com.example.client.ui.vetting.BeneficiaryDetailRoute
import com.example.client.ui.vetting.DecisionRoute
import com.example.client.ui.vetting.VettingListRoute
import com.example.client.ui.vetting.VettingSyncRoute

/**
 * Root of the navigation shell. While there is no role (signed out, or the session expired) it shows the splash screen
 * once and then [login]; and it re-creates the role's graph from scratch whenever the role changes, so a previous role's
 * back stack can never leak into the next one.
 */
@Composable
fun AppRoot(
    role: UserRole?,
    screens: CboScreens = CboScreens(),
    vetting: VettingScreens = VettingScreens(),
    admin: AdminScreens = AdminScreens(),
    login: @Composable () -> Unit
) {
    // The splash is only the branded way in: shown once per launch, never again after someone has been signed in.
    var splashDone by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(role) { if (role != null) splashDone = true }

    if (role == null) {
        if (splashDone) login() else SplashScreen(onContinue = { splashDone = true })
    } else {
        key(role) { AppNavHost(role, screens = screens, vetting = vetting, admin = admin) }
    }
}

/** Which of the two ends of the Done screen a role gets (see [CboScreens.done]). */
typealias DoneScreenContent = @Composable (variant: DoneVariant, onPrimary: () -> Unit, onSecondary: () -> Unit) -> Unit

/**
 * The CBO Collection screens the nav graphs host (the Admin graph hosts the collection form too). The defaults are the
 * real, Hilt-backed screens; navigation tests substitute plain composables so they don't need a Hilt activity.
 */
class CboScreens(
    val home: @Composable (onStartCollection: () -> Unit, onOpenHistory: () -> Unit, onOpenQueue: () -> Unit) -> Unit =
        { start, history, queue -> CboHomeRoute(onStartCollection = start, onOpenHistory = history, onOpenQueue = queue) },
    val collect: @Composable (
        onOpenSignature: (AttachmentKind) -> Unit,
        onOpenPhotos: () -> Unit,
        onSubmitted: () -> Unit,
        onBack: (() -> Unit)?
    ) -> Unit = { sign, photos, submitted, back ->
        Form1Route(onOpenSignature = sign, onOpenPhotos = photos, onSubmitted = submitted, onBack = back)
    },
    val sign: @Composable (kind: AttachmentKind, onBack: () -> Unit) -> Unit =
        { kind, back -> SignatureRoute(kind = kind, onBack = back) },
    val photos: @Composable (onBack: () -> Unit) -> Unit = { PhotosRoute(onBack = it) },
    val done: DoneScreenContent = { variant, primary, secondary ->
        CollectionDoneRoute(variant = variant, onPrimary = primary, onSecondary = secondary)
    },
    val history: @Composable () -> Unit = { HistoryRoute() },
    val sync: @Composable () -> Unit = { CboSyncRoute() }
)

/**
 * Registers only [role]'s nested graph. Routes belonging to other roles do not exist
 * in this NavHost, so they cannot be reached by back navigation or by navigating to them.
 * The role's bottom navigation bar shows on its tab screens only.
 */
@Composable
fun AppNavHost(
    role: UserRole,
    navController: NavHostController = rememberNavController(),
    screens: CboScreens = CboScreens(),
    vetting: VettingScreens = VettingScreens(),
    admin: AdminScreens = AdminScreens()
) {
    val tabs = remember(role) { role.bottomNavItems() }
    val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route

    Column(modifier = Modifier
        .fillMaxSize()
        .background(SaColors.Surface)) {
        Box(modifier = Modifier.weight(1f)) {
            NavHost(navController = navController, startDestination = role.graphRoute()) {
                when (role) {
                    UserRole.CBO_COLLECTION -> cboCollectionGraph(navController, screens)
                    UserRole.VETTING -> vettingGraph(navController, vetting)
                    UserRole.ADMIN -> adminGraph(navController, screens, vetting, admin)
                }
            }
        }
        if (tabs.any { it.route == currentRoute }) {
            BottomNavBar(items = tabs, currentRoute = currentRoute, onNavigate = { navController.navigateToTab(it.route) })
        }
    }
}

/**
 * The Form 2 (Vetting) screens the nav graphs host. As with [CboScreens], the defaults are the real Hilt-backed screens
 * and navigation tests substitute plain composables.
 */
class VettingScreens(
    val list: @Composable (onOpen: (String) -> Unit) -> Unit = { VettingListRoute(onOpen = it) },
    val detail: @Composable (onBack: () -> Unit, onRecordDecision: (String) -> Unit) -> Unit =
        { onBack, onRecordDecision -> BeneficiaryDetailRoute(onBack = onBack, onRecordDecision = onRecordDecision) },
    val decision: @Composable (onBack: () -> Unit) -> Unit = { DecisionRoute(onBack = it) },
    val sync: @Composable () -> Unit = { VettingSyncRoute() }
)

/** The Admin oversight screens the Admin graph hosts. As with [CboScreens], navigation tests substitute plain composables. */
class AdminScreens(
    val overview: @Composable (onOpen: (AdminDestination) -> Unit) -> Unit = { AdminOverviewRoute(onOpen = it) },
    val reports: @Composable () -> Unit = { ReportsRoute() },
    val syncMonitor: @Composable (onBack: () -> Unit) -> Unit = { SyncMonitorRoute(onBack = it) },
    val failedSyncList: @Composable (onBack: () -> Unit, onOpen: (SyncForm, String) -> Unit) -> Unit =
        { onBack, onOpen -> FailedSyncListRoute(onBack = onBack, onOpen = onOpen) },
    val failedSyncRecord: @Composable (onBack: () -> Unit) -> Unit = { FailedSyncDetailRoute(onBack = it) },
    val userActivity: @Composable (onBack: () -> Unit) -> Unit = { UserActivityRoute(onBack = it) }
)
