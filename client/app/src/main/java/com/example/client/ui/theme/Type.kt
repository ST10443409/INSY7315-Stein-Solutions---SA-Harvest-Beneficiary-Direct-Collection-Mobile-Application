package com.example.client.ui.theme

import android.os.Build
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.example.client.R

/** Headline face (from the CBO Collector design): titles and big numbers. */
val Poppins = FontFamily(
    Font(R.font.poppins_medium, FontWeight.Medium),
    Font(R.font.poppins_semibold, FontWeight.SemiBold),
    Font(R.font.poppins_bold, FontWeight.Bold),
)

/**
 * Body / UI face. A variable font (wght), which Android only supports from API 26; on older devices every
 * weight falls back to the font's default weight rather than failing.
 */
@OptIn(ExperimentalTextApi::class)
val Figtree = FontFamily(
    listOf(
        FontWeight.Normal to 400,
        FontWeight.Medium to 500,
        FontWeight.SemiBold to 600,
        FontWeight.Bold to 700,
    ).map { (weight, wght) ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Font(R.font.figtree, weight, variationSettings = FontVariation.Settings(FontVariation.weight(wght)))
        } else {
            Font(R.font.figtree, weight)
        }
    }
)
