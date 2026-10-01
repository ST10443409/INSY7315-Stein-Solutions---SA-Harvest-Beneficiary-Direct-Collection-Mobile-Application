package com.example.client.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.client.ui.theme.Figtree
import com.example.client.ui.theme.GlyphPaths
import com.example.client.ui.theme.SaColors
import com.example.client.ui.theme.StrokeIcon

// Ported from the CBO Collector design demo (Fields.kt). Additions for the real app: an error state, and a
// test tag that lands on the text field itself (so UI tests can type into it).

private val fieldTextStyle = TextStyle(fontFamily = Figtree, fontSize = 15.sp, color = SaColors.Ink)

private fun fieldBorder(isError: Boolean) = if (isError) SaColors.Error else SaColors.inkAlpha(0.16f)

@Composable
fun SaInputField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    keyboardType: KeyboardType = KeyboardType.Text,
    isError: Boolean = false,
    fieldTestTag: String? = null,
) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .background(SaColors.White, shape)
            .border(if (isError) 1.5.dp else 1.dp, fieldBorder(isError), shape),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            if (value.isEmpty()) {
                Text(placeholder, style = fieldTextStyle, color = SaColors.MutedLight)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                textStyle = fieldTextStyle,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                cursorBrush = SolidColor(SaColors.Ink),
                modifier = Modifier
                    .fillMaxWidth()
                    .let { if (fieldTestTag != null) it.testTag(fieldTestTag) else it },
            )
        }
    }
}

@Composable
fun SaTextArea(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    minLines: Int = 3,
    fieldTestTag: String? = null,
) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(SaColors.White, shape)
            .border(1.dp, SaColors.inkAlpha(0.16f), shape)
            .padding(horizontal = 18.dp, vertical = 14.dp),
    ) {
        if (value.isEmpty()) {
            Text(placeholder, style = fieldTextStyle.copy(lineHeight = 22.sp), color = SaColors.MutedLight)
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = fieldTextStyle.copy(lineHeight = 22.sp),
            minLines = minLines,
            cursorBrush = SolidColor(SaColors.Ink),
            modifier = Modifier
                .fillMaxWidth()
                .let { if (fieldTestTag != null) it.testTag(fieldTestTag) else it },
        )
    }
}

@Composable
fun SaSelectField(
    value: String,
    options: List<String>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Select…",
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 48.dp)
                .clip(shape)
                .background(SaColors.White, shape)
                .border(1.dp, SaColors.inkAlpha(0.16f), shape)
                .clickable { expanded = true }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = value.ifEmpty { placeholder },
                style = fieldTextStyle,
                color = if (value.isEmpty()) SaColors.MutedLight else SaColors.Ink,
                modifier = Modifier.weight(1f),
            )
            StrokeIcon(pathData = GlyphPaths.ChevronRight, tint = SaColors.MutedLight, modifier = Modifier.size(16.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option, style = fieldTextStyle) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
fun SaDashedActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
    content: @Composable RowScope.() -> Unit,
) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .clip(shape)
            .background(SaColors.SurfaceAlt, shape)
            .dashedBorder(SaColors.DashedBorder, cornerRadius = 10.dp)
            .clickable(onClick = onClick)
            .padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content()
    }
}
