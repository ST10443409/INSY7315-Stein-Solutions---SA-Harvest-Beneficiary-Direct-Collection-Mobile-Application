package com.example.client.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.client.R
import com.example.client.ui.theme.Figtree
import com.example.client.ui.theme.Poppins
import com.example.client.ui.theme.SaColors

object SyncTabTags {
    const val SCREEN = "screen_sync_tab"
    const val PANEL = "sync_tab_panel"
    const val FAILED = "sync_tab_failed"
    const val SYNC_NOW = "sync_tab_sync_now"
    const val SIGN_OUT = "sync_tab_sign_out"
}

/**
 * The demo's Sync tab: a title, a panel saying whether the device is online, the queue, a sync button and a way out.
 * Each role's Sync tab fills in its own queue and its own counts; this only lays them out.
 *
 * The demo's "Return to role selection" is a sign-out here, because the role comes from the account.
 */
@Composable
fun SyncTabScreen(
    subtitle: String,
    online: Boolean,
    syncLabel: String,
    syncEnabled: Boolean,
    onSync: () -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
    failedMessage: String? = null,
    queue: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(SaColors.Surface)
            .verticalScroll(rememberScrollState())
            .padding(20.dp, 20.dp, 20.dp, 26.dp)
            .testTag(SyncTabTags.SCREEN)
    ) {
        Text(
            stringResource(R.string.sync_title),
            fontFamily = Poppins, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, color = SaColors.Ink
        )
        Text(
            subtitle,
            fontFamily = Figtree, fontSize = 12.5.sp, color = SaColors.MutedLight,
            modifier = Modifier.padding(top = 4.dp, bottom = 18.dp)
        )
        val panelBg = if (online) SaColors.SurfaceAlt else SaColors.Ink
        val panelText = if (online) SaColors.Ink else SaColors.Cream
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(panelBg)
                .padding(18.dp)
                .testTag(SyncTabTags.PANEL)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(SaColors.YellowDark)
                )
                Text(
                    stringResource(if (online) R.string.status_online else R.string.status_offline),
                    fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp, color = panelText,
                    modifier = Modifier.padding(start = 10.dp)
                )
            }
            Text(
                stringResource(if (online) R.string.sync_online_body else R.string.sync_offline_body),
                fontFamily = Figtree, fontSize = 12.5.sp, lineHeight = 19.sp, color = panelText.copy(alpha = 0.85f),
                modifier = Modifier.padding(top = 8.dp)
            )
        }
        if (failedMessage != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(SaColors.TagErrorBg)
                    .padding(18.dp)
                    .testTag(SyncTabTags.FAILED),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(SaColors.Error)
                )
                Text(
                    failedMessage,
                    fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp, lineHeight = 19.sp,
                    color = SaColors.TagErrorText,
                    modifier = Modifier.padding(start = 10.dp)
                )
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = queue
        )
        FilledPillButton(
            onClick = { if (syncEnabled) onSync() },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 18.dp)
                .testTag(SyncTabTags.SYNC_NOW),
            containerColor = if (syncEnabled) SaColors.Yellow else SaColors.Divider,
            contentColor = if (syncEnabled) SaColors.Ink else SaColors.Muted,
            contentPadding = PaddingValues(16.dp)
        ) {
            Text(
                syncLabel,
                fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
                color = if (syncEnabled) SaColors.Ink else SaColors.Muted
            )
        }
        OutlinePillButton(
            onClick = onSignOut,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
                .testTag(SyncTabTags.SIGN_OUT),
            contentPadding = PaddingValues(15.dp)
        ) {
            Text(
                stringResource(R.string.sync_sign_out),
                fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 14.5.sp, color = SaColors.Ink
            )
        }
    }
}
