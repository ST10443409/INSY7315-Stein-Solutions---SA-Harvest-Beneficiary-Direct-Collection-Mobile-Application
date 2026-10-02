package com.example.client.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.client.R
import com.example.client.ui.theme.Figtree
import com.example.client.ui.theme.Poppins
import com.example.client.ui.theme.SaColors
import com.example.client.ui.theme.StrokeIcon

// Shell pieces ported from the CBO Collector design demo (BottomNavBar.kt, EyebrowLabel in Misc.kt, and the greeting and
// online strip at the top of its Home screens). Changes: nav items name a route rather than the demo's Screen enum, and
// every piece takes real values (the demo hard-codes names, dates and queue counts).

/** One tab of a role's bottom navigation bar. */
data class BottomNavItem(val route: String, @StringRes val label: Int, val glyph: String)

fun bottomNavTag(route: String) = "nav_$route"

@Composable
fun BottomNavBar(
    items: List<BottomNavItem>,
    currentRoute: String?,
    onNavigate: (BottomNavItem) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier
        .fillMaxWidth()
        .background(SaColors.SurfaceAlt)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(SaColors.inkAlpha(0.1f))
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 8.dp)
        ) {
            items.forEach { item ->
                val active = item.route == currentRoute
                val labelColor = if (active) SaColors.Ink else SaColors.MutedLight
                val label = stringResource(item.label)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .testTag(bottomNavTag(item.route))
                        .semantics(mergeDescendants = true) {
                            role = Role.Tab
                            selected = active
                            contentDescription = label
                        }
                        .clickable { onNavigate(item) }
                        .padding(top = 9.dp, bottom = 6.dp, start = 4.dp, end = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .clip(PillShape)
                            .background(if (active) SaColors.inkAlpha(0.08f) else Color.Transparent, PillShape)
                            .padding(horizontal = 14.dp, vertical = 4.dp)
                    ) {
                        StrokeIcon(pathData = item.glyph, tint = labelColor, modifier = Modifier.size(21.dp))
                    }
                    Text(label, fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, color = labelColor)
                }
            }
        }
    }
}

/** A small uppercase section label, as above the demo's "Today" and "Needs attention" lists. */
@Composable
fun EyebrowLabel(text: String, modifier: Modifier = Modifier, color: Color = SaColors.MutedLight) {
    Text(
        text = text.uppercase(),
        fontFamily = Figtree,
        fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp,
        letterSpacing = 1.76.sp,
        color = color,
        modifier = modifier
    )
}

/** The top of a role's home screen: today's date, a greeting, the signed-in user's role, and their initials. */
@Composable
fun GreetingHeader(
    date: String,
    title: String,
    subtitle: String,
    initials: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(22.dp, 22.dp, 22.dp, 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(date, fontFamily = Figtree, fontSize = 12.5.sp, color = SaColors.MutedLight)
            Text(
                title,
                fontFamily = Poppins, fontWeight = FontWeight.SemiBold, fontSize = 25.sp, color = SaColors.Ink,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp)
            )
            Text(subtitle, fontFamily = Figtree, fontSize = 12.5.sp, color = SaColors.MutedLight, modifier = Modifier.padding(top = 3.dp))
        }
        Box(
            modifier = Modifier
                .padding(start = 12.dp)
                .size(42.dp)
                .clip(CircleShape)
                .background(SaColors.Divider),
            contentAlignment = Alignment.Center
        ) {
            Text(initials, fontFamily = Poppins, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = SaColors.Ink)
        }
    }
}

/**
 * The strip under the greeting: whether the device is online, and a link to the sync queue. It turns red while
 * [failedCount] records need attention, so a failure is never only visible on the Sync tab.
 */
@Composable
fun ConnectivityPill(
    online: Boolean,
    failedCount: Int,
    onOpenQueue: () -> Unit,
    modifier: Modifier = Modifier
) {
    val failed = failedCount > 0
    val bg = when {
        failed -> SaColors.TagErrorBg
        online -> SaColors.SurfaceAlt
        else -> SaColors.TagWarnBg
    }
    val text = when {
        failed -> SaColors.TagErrorText
        online -> SaColors.Ink
        else -> SaColors.LinkGold
    }
    val shape = RoundedCornerShape(999.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp)
            .padding(bottom = 18.dp)
            .clip(shape)
            .background(bg, shape)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(if (failed) SaColors.Error else SaColors.YellowDark)
        )
        Text(
            when {
                failed -> stringResource(R.string.status_failed_count, failedCount)
                online -> stringResource(R.string.status_online)
                else -> stringResource(R.string.status_offline)
            },
            fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = text,
            modifier = Modifier
                .weight(1f)
                .padding(start = 10.dp)
        )
        Text(
            stringResource(if (online) R.string.status_view_queue else R.string.status_queue),
            fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp, color = if (failed) text else SaColors.LinkGold,
            modifier = Modifier.clickable(onClick = onOpenQueue)
        )
    }
}
