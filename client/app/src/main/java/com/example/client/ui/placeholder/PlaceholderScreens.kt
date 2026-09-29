package com.example.client.ui.placeholder

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** Test tags used to assert which screen is showing. */
object ScreenTags {
    const val FORM1 = "screen_form1"
    const val FORM2 = "screen_form2"
    const val ADMIN_DASHBOARD = "screen_admin_dashboard"
    const val SYNC_MONITOR = "screen_sync_monitor"
    const val SIGNED_OUT = "screen_signed_out"
    const val OPEN_FORM1 = "button_open_form1"
    const val OPEN_FORM2 = "button_open_form2"
    const val OPEN_SYNC_MONITOR = "button_open_sync_monitor"
}

// Placeholders only. Real UI: Form 1 (#33), Form 2 (#44).

@Composable
fun Form1PlaceholderScreen() = PlaceholderScreen("Form 1", ScreenTags.FORM1)

@Composable
fun Form2PlaceholderScreen() = PlaceholderScreen("Form 2", ScreenTags.FORM2)

@Composable
fun SyncMonitorPlaceholderScreen() = PlaceholderScreen("Sync monitoring", ScreenTags.SYNC_MONITOR)

// TODO(#27): route to the login screen once it exists.
@Composable
fun SignedOutPlaceholderScreen() = PlaceholderScreen("Not signed in", ScreenTags.SIGNED_OUT)

@Composable
fun AdminDashboardPlaceholderScreen(
    onOpenForm1: () -> Unit,
    onOpenForm2: () -> Unit,
    onOpenSyncMonitor: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .testTag(ScreenTags.ADMIN_DASHBOARD),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Admin dashboard", style = MaterialTheme.typography.headlineMedium)
        Button(onClick = onOpenForm1, modifier = Modifier.testTag(ScreenTags.OPEN_FORM1)) {
            Text("Open Form 1")
        }
        Button(onClick = onOpenForm2, modifier = Modifier.testTag(ScreenTags.OPEN_FORM2)) {
            Text("Open Form 2")
        }
        Button(onClick = onOpenSyncMonitor, modifier = Modifier.testTag(ScreenTags.OPEN_SYNC_MONITOR)) {
            Text("Sync monitoring")
        }
    }
}

@Composable
private fun PlaceholderScreen(title: String, tag: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag(tag),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
    }
}
