package com.example.client.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.client.ui.theme.Figtree
import com.example.client.ui.theme.GlyphPaths
import com.example.client.ui.theme.Poppins
import com.example.client.ui.theme.SaColors
import com.example.client.ui.theme.StrokeIcon

/** One line of a receipt: what it is, and its value. */
data class ReceiptRow(val key: String, val value: String)

/**
 * The "it is done" screen the demo shows after a collection, a vetting or an admin decision: a yellow check, a title,
 * a short explanation, a receipt card, and two ways onward. It only lays out what it is given.
 */
@Composable
fun ReceiptScreen(
    title: String,
    body: String,
    rows: List<ReceiptRow>,
    primaryLabel: String,
    onPrimary: () -> Unit,
    secondaryLabel: String,
    onSecondary: () -> Unit,
    modifier: Modifier = Modifier,
    titleTag: String? = null,
    primaryTag: String? = null,
    secondaryTag: String? = null
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(SaColors.Surface)
            .verticalScroll(rememberScrollState())
            .padding(26.dp, 32.dp, 26.dp, 32.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(SaColors.Yellow),
            contentAlignment = Alignment.Center
        ) {
            StrokeIcon(pathData = GlyphPaths.Check, tint = SaColors.Ink, strokeWidth = 2.75f, modifier = Modifier.size(34.dp))
        }
        Text(
            title,
            fontFamily = Poppins, fontWeight = FontWeight.SemiBold, fontSize = 30.sp, color = SaColors.Ink,
            modifier = Modifier
                .padding(top = 24.dp, bottom = 10.dp)
                .let { if (titleTag != null) it.testTag(titleTag) else it }
        )
        Text(body, fontFamily = Figtree, fontSize = 14.5.sp, lineHeight = 23.sp, color = SaColors.Muted)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp, bottom = 26.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(SaColors.White)
                .border(1.dp, SaColors.inkAlpha(0.12f), RoundedCornerShape(14.dp))
        ) {
            rows.forEachIndexed { index, row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp, 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(row.key, fontFamily = Figtree, fontSize = 13.sp, color = SaColors.MutedLight)
                    Text(
                        row.value,
                        fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp, color = SaColors.Ink,
                        modifier = Modifier.padding(start = 16.dp)
                    )
                }
                if (index != rows.lastIndex) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(SaColors.inkAlpha(0.08f))
                    )
                }
            }
        }

        FilledPillButton(
            onClick = onPrimary,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 10.dp)
                .let { if (primaryTag != null) it.testTag(primaryTag) else it },
            contentPadding = PaddingValues(16.dp)
        ) {
            Text(primaryLabel, fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = SaColors.Ink)
        }
        OutlinePillButton(
            onClick = onSecondary,
            modifier = Modifier
                .fillMaxWidth()
                .let { if (secondaryTag != null) it.testTag(secondaryTag) else it },
            contentPadding = PaddingValues(15.dp)
        ) {
            Text(secondaryLabel, fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 14.5.sp, color = SaColors.Ink)
        }
    }
}
