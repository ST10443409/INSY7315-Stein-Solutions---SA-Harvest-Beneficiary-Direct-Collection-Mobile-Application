package com.example.client.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.client.R
import com.example.client.ui.theme.Figtree
import com.example.client.ui.theme.GlyphPaths
import com.example.client.ui.theme.SaColors
import com.example.client.ui.theme.StrokeIcon

object AccordionTags {
    fun header(id: String) = "accordion_header_$id"
    fun body(id: String) = "accordion_body_$id"
}

/**
 * One expandable / retractable section, drawn like the demo's vetting form: a numbered badge, a white card with a yellow
 * outline while open, a dark card with a tick once the section has been [reviewed] and is closed, and a quiet grey card
 * otherwise.
 *
 * One expandable / retractable section. Tapping the header toggles [expanded]; the state is owned by the caller so a
 * list of sections can offer "expand all / collapse all" and keep its state across rotation. The body is only
 * composed while open. The header announces its state ("Expanded" / "Collapsed") to screen readers.
 */
@Composable
fun AccordionSection(
    id: String,
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    number: Int? = null,
    reviewed: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    val dark = reviewed && !expanded
    val container = when {
        expanded -> SaColors.White
        reviewed -> SaColors.Ink
        else -> SaColors.SurfaceAlt
    }
    val outline = when {
        expanded -> SaColors.Yellow
        reviewed -> SaColors.Divider
        else -> SaColors.inkAlpha(0.12f)
    }
    val titleColor = if (dark) SaColors.Cream else SaColors.Ink
    val quietColor = if (dark) SaColors.Cream.copy(alpha = 0.6f) else SaColors.MutedLight
    val chevron by animateFloatAsState(if (expanded) 90f else 0f, label = "accordionChevron")
    val stateText = stringResource(if (expanded) R.string.accordion_expanded else R.string.accordion_collapsed)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(container, shape)
            .border(1.dp, outline, shape)
            .animateContentSize()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClick = onToggle)
                .defaultMinSize(minHeight = 56.dp)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .testTag(AccordionTags.header(id))
                .semantics { stateDescription = stateText },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (number != null) {
                val lit = reviewed || expanded
                Box(
                    modifier = Modifier.size(26.dp).clip(CircleShape).background(if (lit) SaColors.Yellow else SaColors.Divider),
                    contentAlignment = Alignment.Center,
                ) {
                    if (reviewed) {
                        StrokeIcon(pathData = GlyphPaths.Check, modifier = Modifier.size(13.dp), tint = SaColors.Ink)
                    } else {
                        Text(
                            number.toString(),
                            fontFamily = Figtree, fontWeight = FontWeight.Bold, fontSize = 11.sp,
                            color = if (lit) SaColors.Ink else SaColors.Muted,
                        )
                    }
                }
            }
            Column(modifier = Modifier.weight(1f).padding(start = if (number != null) 12.dp else 0.dp)) {
                Text(title, fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 14.5.sp, color = titleColor)
                if (subtitle != null) {
                    Text(subtitle, fontFamily = Figtree, fontSize = 11.5.sp, color = quietColor)
                }
            }
            StrokeIcon(
                pathData = GlyphPaths.ChevronRight,
                modifier = Modifier.size(18.dp).rotate(chevron),
                tint = quietColor,
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column(modifier = Modifier.fillMaxWidth().testTag(AccordionTags.body(id))) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(SaColors.Divider))
                content()
            }
        }
    }
}
