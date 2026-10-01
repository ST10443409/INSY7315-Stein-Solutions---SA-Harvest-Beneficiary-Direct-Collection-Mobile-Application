package com.example.client.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp

/**
 * The product is a single fixed brand look (not Material, not dark-mode aware) —
 * this ColorScheme only exists to seed the handful of Material3 primitives
 * (TextField, Button) we reuse as unstyled building blocks.
 */
private val SaColorScheme = lightColorScheme(
    primary = SaColors.Yellow,
    onPrimary = SaColors.Ink,
    secondary = SaColors.Ink,
    onSecondary = SaColors.White,
    background = SaColors.Surface,
    onBackground = SaColors.Ink,
    surface = SaColors.White,
    onSurface = SaColors.Ink,
    outline = SaColors.inkAlpha(0.16f),
)

private val SaTypography = Typography(
    bodyLarge = TextStyle(fontFamily = Figtree, fontSize = 15.sp),
)

@Composable
fun CBOCollectorTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = SaColorScheme,
        typography = SaTypography,
        content = content,
    )
}
