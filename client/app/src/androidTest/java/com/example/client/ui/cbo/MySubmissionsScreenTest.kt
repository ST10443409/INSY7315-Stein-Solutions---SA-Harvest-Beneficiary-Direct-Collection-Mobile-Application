package com.example.client.ui.cbo

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.client.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Each way a submission can end up is shown to the collector with its own explanation. */
@RunWith(AndroidJUnit4::class)
class MySubmissionsScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun item(id: String, display: SubmissionDisplay) =
        SubmissionItem(id = id, donorName = "Donor $id", createdAt = 1_700_000_000_000L, display = display)

    private fun show(vararg items: SubmissionItem) {
        composeRule.setContent { MySubmissionsScreen(SubmissionsUiState(items.toList()), onBack = {}) }
    }

    private fun text(id: Int) = composeRule.activity.getString(id)

    private fun scrollTo(id: String) {
        composeRule.onNodeWithTag(SubmissionsTags.LIST).performScrollToNode(androidx.compose.ui.test.hasTestTag(SubmissionsTags.item(id)))
    }

    @Test
    fun aDuplicate_isLabelledAsOne_andExplained() {
        show(item("dup", SubmissionDisplay.FAILED_DUPLICATE))

        composeRule.onNodeWithTag(SubmissionsTags.item("dup")).assertIsDisplayed()
        composeRule.onNodeWithText(text(R.string.submissions_status_duplicate)).assertIsDisplayed()
        composeRule.onNodeWithText(text(R.string.submissions_hint_duplicate)).assertIsDisplayed()
    }

    @Test
    fun aRejectedRecord_isLabelledAsRejected_andExplained() {
        show(item("rej", SubmissionDisplay.FAILED_REJECTED))

        composeRule.onNodeWithText(text(R.string.submissions_status_rejected)).assertIsDisplayed()
        composeRule.onNodeWithText(text(R.string.submissions_hint_rejected)).assertIsDisplayed()
    }

    @Test
    fun aRecordStillBeingRetried_saysTheAppWillTryAgain() {
        show(item("again", SubmissionDisplay.FAILED_WILL_RETRY))

        composeRule.onNodeWithText(text(R.string.submissions_hint_failed_retry)).assertIsDisplayed()
    }

    @Test
    fun aRecordThatRanOutOfRetries_tellsTheCollectorToAskForHelp() {
        show(item("final", SubmissionDisplay.FAILED_FINAL))

        composeRule.onNodeWithText(text(R.string.submissions_hint_failed_final)).assertIsDisplayed()
    }

    @Test
    fun everyKindOfSubmission_isListed() {
        val all = SubmissionDisplay.values().mapIndexed { i, d -> item("r$i", d) }
        show(*all.toTypedArray())

        all.forEach {
            scrollTo(it.id)
            composeRule.onNodeWithTag(SubmissionsTags.item(it.id)).assertIsDisplayed()
        }
    }
}
