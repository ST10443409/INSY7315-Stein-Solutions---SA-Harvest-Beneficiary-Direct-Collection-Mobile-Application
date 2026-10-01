package com.example.client.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.client.data.local.entity.Tone
import com.example.client.ui.theme.Figtree
import com.example.client.ui.theme.Poppins
import com.example.client.ui.theme.SaColors

// Ported from the CBO Collector design demo (Misc.kt). Changes: TagBadge also takes a raw palette (the demo's
// tones have no red, and Tone is a Room enum that must not grow), and ScreenHeader's back button is optional
// because Form 1 is the app's start screen.

data class TagPalette(val bg: Color, val text: Color)

fun Tone.palette(): TagPalette = when (this) {
    Tone.OK -> TagPalette(SaColors.TagOkBg, SaColors.TagOkText)
    Tone.WARN -> TagPalette(SaColors.TagWarnBg, SaColors.TagWarnText)
    Tone.NEW -> TagPalette(SaColors.TagNewBg, SaColors.TagNewText)
}

val ErrorTagPalette = TagPalette(SaColors.TagErrorBg, SaColors.TagErrorText)

@Composable
fun TagBadge(text: String, tone: Tone, modifier: Modifier = Modifier) = TagBadge(text, tone.palette(), modifier)

@Composable
fun TagBadge(text: String, palette: TagPalette, modifier: Modifier = Modifier) {
    Text(
        text = text,
        fontFamily = Figtree,
        fontWeight = FontWeight.Bold,
        fontSize = 10.5.sp,
        color = palette.text,
        modifier = modifier
            .clip(PillShape)
            .background(palette.bg, PillShape)
            .padding(horizontal = 9.dp, vertical = 4.dp),
    )
}

@Composable
fun ScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    subtitle: String? = null,
    trailing: @Composable () -> Unit = {},
) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            BackCircleButton(onClick = onBack)
            Spacer(Modifier.size(12.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                fontFamily = Poppins,
                fontWeight = FontWeight.SemiBold,
                fontSize = 19.sp,
                color = SaColors.Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    fontFamily = Figtree,
                    fontSize = 12.sp,
                    color = SaColors.MutedLight,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing()
    }
}
