package com.example.client.ui.admin

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.client.R
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
    fun onlyTheUnbuiltSections_areMarkedComingSoon() {
        composeRule.setContent { AdminDashboardScreen(onOpen = {}) }
        val tag = text(R.string.admin_coming_soon_tag)

        AdminDestination.placeholders.forEach { composeRule.onNodeWithTag(AdminTags.entry(it)).performScrollTo().assertTextContains(tag) }
        listOf(AdminDestination.FORM1, AdminDestination.FORM2).forEach {
            composeRule.onNodeWithTag(AdminTags.entry(it)).performScrollTo().assert(!hasText(tag, substring = true))
        }
    }

    @Test
    fun tappingAnEntry_opensThatDestination() {
        val opened = mutableListOf<AdminDestination>()
        composeRule.setContent { AdminDashboardScreen(onOpen = { opened += it }) }

        AdminDestination.values().forEach { composeRule.onNodeWithTag(AdminTags.entry(it)).performScrollTo().performClick() }

        assertEquals(AdminDestination.values().toList(), opened)
    }

    @Test
    fun aPlaceholderSection_isLabelled_andSaysWhatItWillShow() {
        composeRule.setContent { AdminSectionScreen(AdminDestination.USER_ACTIVITY, onBack = {}) }

        composeRule.onNodeWithTag(AdminTags.section(AdminDestination.USER_ACTIVITY)).assertIsDisplayed()
        composeRule.onNodeWithTag(AdminTags.SECTION_COMING_SOON).assertTextContains(text(R.string.admin_coming_soon_title))
        composeRule.onNodeWithTag(AdminTags.SECTION_COMING_SOON).assertTextContains(text(AdminDestination.USER_ACTIVITY.description))
    }
}
