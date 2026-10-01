package com.example.client.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Flat colour tokens lifted directly from the CBO Collector design spec.
 * Kept as literal hexes (not a Material colour scheme) because the design
 * is a bespoke brand palette, not Material's.
 */
object SaColors {
    val Yellow = Color(0xFFFFD400)
    val YellowDark = Color(0xFFE6BF00)
    val YellowHover = Color(0xFFFFDE40)
    val YellowTickBg = Color(0xFFFFF3C2)

    val Ink = Color(0xFF1A1A1A)
    val InkSoft = Color(0xFF2B2B28)
    val Muted = Color(0xFF6D6D68)
    val MutedLight = Color(0xFF8F8F89)
    val Faint = Color(0xFFA8A8A2)

    val AppBg = Color(0xFFECEAE4)
    val Surface = Color(0xFFF4F3EF)
    val SurfaceAlt = Color(0xFFF8F7F3)
    val Divider = Color(0xFFDEDCD5)
    val DashedBorder = Color(0xFFCFCEC8)
    val White = Color(0xFFFFFFFF)

    val SplashBg = Color(0xFF0D0C0A)
    val SplashCard = Color(0xFF1C1A15)
    val SplashBlobA = Color(0xFF262218)
    val SplashBlobB = Color(0xFF231F16)
    val SplashBlobC = Color(0xFF3A3325)
    val Cream = Color(0xFFF5F2E8)

    val LinkGold = Color(0xFF8A6A00)
    val LinkGoldHover = Color(0xFF6B5400)

    val TagOkBg = Color(0xFFE9F0E9)
    val TagOkText = Color(0xFF2F5D3A)
    val TagWarnBg = Color(0xFFFFF3C2)
    val TagWarnText = Color(0xFF7A6100)
    val TagNewBg = Color(0xFFECEAE4)
    val TagNewText = Color(0xFF6D6D68)

    // Not in the demo (its tags only have OK / WARN / NEW): a red for sync failures, so Failed is never mistaken for Pending.
    val TagErrorBg = Color(0xFFFBE4E1)
    val TagErrorText = Color(0xFF9B2318)
    val Error = Color(0xFFB3261B)

    fun inkAlpha(alpha: Float) = Ink.copy(alpha = alpha)
}
