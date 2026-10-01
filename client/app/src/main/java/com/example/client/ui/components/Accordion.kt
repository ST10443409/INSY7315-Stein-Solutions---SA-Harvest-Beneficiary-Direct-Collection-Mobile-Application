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
import com.example.client.ui.theme.Poppins
import com.example.client.ui.theme.SaColors
import com.example.client.ui.theme.StrokeIcon

object AccordionTags {
    fun header(id: String) = "accordion_header_$id"
    fun body(id: String) = "accordion_body_$id"
}

/**
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
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    val chevron by animateFloatAsState(if (expanded) 90f else 0f, label = "accordionChevron")
    val stateText = stringResource(if (expanded) R.string.accordion_expanded else R.string.accordion_collapsed)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(SaColors.White, shape)
            .border(1.dp, SaColors.inkAlpha(0.12f), shape)
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
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontFamily = Poppins, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = SaColors.Ink)
                if (subtitle != null) {
                    Text(subtitle, fontFamily = Figtree, fontSize = 12.sp, color = SaColors.MutedLight)
                }
            }
            StrokeIcon(
                pathData = GlyphPaths.ChevronRight,
                modifier = Modifier.size(18.dp).rotate(chevron),
                tint = SaColors.Muted,
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
