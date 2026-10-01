package com.example.client.ui.admin

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The Admin dashboard and its placeholder sections, on a device. */
@RunWith(AndroidJUnit4::class)
class AdminShellTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun text(id: Int) = composeRule.activity.getString(id)

    @Test
    fun theDashboard_showsEveryDestination_withItsTitleAndDescription() {
        composeRule.setContent { AdminDashboardScreen(onOpen = {}) }

        AdminDestination.values().forEach { destination ->
            val entry = composeRule.onNodeWithTag(AdminTags.entry(destination)).performScrollTo()
            entry.assertIsDisplayed()
            entry.assertTextContains(text(destination.title))
            entry.assertTextContains(text(destination.description))
        }
    }

    @Test
    fun tappingAnEntry_opensThatDestination() {
        val opened = mutableListOf<AdminDestination>()
        composeRule.setContent { AdminDashboardScreen(onOpen = { opened += it }) }

        AdminDestination.values().forEach { composeRule.onNodeWithTag(AdminTags.entry(it)).performScrollTo().performClick() }

        assertEquals(AdminDestination.values().toList(), opened)
    }
}
