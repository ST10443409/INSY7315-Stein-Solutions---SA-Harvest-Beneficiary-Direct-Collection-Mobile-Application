package com.example.client.ui.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.client.R
import com.example.client.ui.components.CardButton
import com.example.client.ui.components.ScreenHeader
import com.example.client.ui.theme.CBOCollectorTheme
import com.example.client.ui.theme.Figtree
import com.example.client.ui.theme.GlyphPaths
import com.example.client.ui.theme.Poppins
import com.example.client.ui.theme.SaColors
import com.example.client.ui.theme.StrokeIcon

object AdminTags {
    const val DASHBOARD = "screen_admin_dashboard"

    /** The dashboard entry for one destination. */
    fun entry(destination: AdminDestination) = "admin_entry_${destination.name.lowercase()}"
}

/**
 * The Admin landing screen: a card for each thing an Admin can open, grouped into the two workflows and the oversight
 * sections. Reaching it is already restricted to the ADMIN role by the navigation graph; this screen only lays it out.
 */
@Composable
fun AdminDashboardScreen(onOpen: (AdminDestination) -> Unit) = CBOCollectorTheme {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SaColors.Surface)
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
            .testTag(AdminTags.DASHBOARD),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ScreenHeader(
            title = stringResource(R.string.admin_title),
            subtitle = stringResource(R.string.admin_subtitle),
            modifier = Modifier.padding(bottom = 8.dp)
        )

        AdminGroup.values().forEach { group ->
            Text(
                stringResource(group.titleRes()),
                fontFamily = Poppins, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = SaColors.MutedLight,
                modifier = Modifier.padding(top = 8.dp)
            )
            AdminDestination.values().filter { it.group == group }.forEach { destination ->
                DestinationCard(destination, onClick = { onOpen(destination) })
            }
        }
    }
}

@Composable
private fun DestinationCard(destination: AdminDestination, onClick: () -> Unit) {
    CardButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(AdminTags.entry(destination))
            .semantics(mergeDescendants = true) {},
        contentPadding = androidx.compose.foundation.layout.PaddingValues(18.dp)
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(destination.title),
                fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 15.5.sp, color = SaColors.Ink
            )
            Text(
                stringResource(destination.description),
                fontFamily = Figtree, fontSize = 13.sp, lineHeight = 19.sp, color = SaColors.Muted
            )
        }
        StrokeIcon(pathData = GlyphPaths.ChevronRight, modifier = Modifier.size(18.dp), tint = SaColors.Muted)
    }
}


private fun AdminGroup.titleRes() = when (this) {
    AdminGroup.WORKFLOWS -> R.string.admin_group_workflows
    AdminGroup.OVERSIGHT -> R.string.admin_group_oversight
}

