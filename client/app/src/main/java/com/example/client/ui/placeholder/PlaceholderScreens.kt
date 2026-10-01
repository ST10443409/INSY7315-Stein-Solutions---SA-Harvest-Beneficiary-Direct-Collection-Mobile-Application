package com.example.client.ui.placeholder

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

/** Test tags used to assert which screen is showing. */
object ScreenTags {
    const val FORM1 = "screen_form1"
    const val FORM2 = "screen_form2"
}

// Stand-ins used by navigation tests so they need no Hilt. The real screens are Form 1 (#33), Form 2 (#44) and the Admin
// sections (ui/admin).

@Composable
fun Form1PlaceholderScreen() = PlaceholderScreen("Form 1", ScreenTags.FORM1)

@Composable
fun Form2PlaceholderScreen() = PlaceholderScreen("Form 2", ScreenTags.FORM2)

@Composable
private fun PlaceholderScreen(title: String, tag: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag(tag),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
    }
}
