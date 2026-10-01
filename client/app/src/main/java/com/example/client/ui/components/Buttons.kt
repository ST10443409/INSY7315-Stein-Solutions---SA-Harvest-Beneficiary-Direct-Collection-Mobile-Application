package com.example.client.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.client.ui.theme.GlyphPaths
import com.example.client.ui.theme.SaColors
import com.example.client.ui.theme.StrokeIcon

val PillShape = RoundedCornerShape(999.dp)

@Composable
fun FilledPillButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = SaColors.Yellow,
    contentColor: Color = SaColors.Ink,
    contentPadding: PaddingValues = PaddingValues(vertical = 15.dp, horizontal = 22.dp),
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier
            .clip(PillShape)
            .background(if (enabled) containerColor else SaColors.Divider, PillShape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(contentPadding),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) { content() }
    }
}

@Composable
fun OutlinePillButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentColor: Color = SaColors.Ink,
    borderColor: Color = SaColors.inkAlpha(0.16f),
    contentPadding: PaddingValues = PaddingValues(vertical = 14.dp, horizontal = 22.dp),
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier
            .clip(PillShape)
            .border(1.dp, borderColor, PillShape)
            .clickable(onClick = onClick)
            .padding(contentPadding),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) { content() }
    }
}

/** A rounded rectangle "card button" — used for tappable list rows and dashboard cards. */
@Composable
fun CardButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = SaColors.White,
    borderColor: Color? = SaColors.inkAlpha(0.12f),
    shape: RoundedCornerShape = RoundedCornerShape(14.dp),
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier
            .clip(shape)
            .background(containerColor, shape)
            .let { if (borderColor != null) it.border(1.dp, borderColor, shape) else it }
            .clickable(onClick = onClick)
            .padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content()
    }
}

@Composable
fun BackCircleButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 38.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .border(1.dp, SaColors.inkAlpha(0.16f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        StrokeIcon(pathData = GlyphPaths.ChevronBack, modifier = Modifier.size(18.dp))
    }
}
