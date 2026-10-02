package com.example.client.ui.admin

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The Reports tab shows exactly the four figures it is given. */
@RunWith(AndroidJUnit4::class)
class ReportsScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun showsTheCollectionsAndTheThreeVettingFigures() {
        composeRule.setContent { ReportsScreen(ReportsUiState(collections = 14, approved = 6, flagged = 2, denied = 3)) }

        composeRule.onNodeWithTag(ReportsTags.SCREEN).assertIsDisplayed()
        composeRule.onNodeWithTag(ReportsTags.COLLECTIONS).assertTextEquals("14")
        composeRule.onNodeWithTag(ReportsTags.APPROVED).performScrollTo().assertTextEquals("6")
        composeRule.onNodeWithTag(ReportsTags.FLAGGED).performScrollTo().assertTextEquals("2")
        composeRule.onNodeWithTag(ReportsTags.DENIED).performScrollTo().assertTextEquals("3")
    }

    @Test
    fun withNothingRecorded_everyFigureIsZero() {
        composeRule.setContent { ReportsScreen(ReportsUiState()) }

        listOf(ReportsTags.COLLECTIONS, ReportsTags.APPROVED, ReportsTags.FLAGGED, ReportsTags.DENIED).forEach {
            composeRule.onNodeWithTag(it).performScrollTo().assertTextEquals("0")
        }
    }
}
