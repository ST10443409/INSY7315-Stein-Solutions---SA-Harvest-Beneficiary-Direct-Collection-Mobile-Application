package com.example.client.ui.cbo

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.client.R
import com.example.client.data.attachments.CaptureTarget
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The photos screen and the "add a photo" sheet: tapping a slot offers the camera and the gallery. */
@RunWith(AndroidJUnit4::class)
class PhotosScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val removed = mutableListOf<AttachmentSlot>()

    private val actions = AttachmentActions(
        onPhotoPicked = { _, _ -> },
        onNewCaptureTarget = { CaptureTarget("content://fake", "/fake") },
        onCaptureResult = { },
        onRemove = { removed += it }
    )

    private fun text(id: Int, vararg args: Any) = composeRule.activity.getString(id, *args)

    private fun photo() = DraftAttachment(path = "/no/such/file.jpg", mimeType = "image/jpeg", sizeBytes = 1)

    private fun show(form: Form1FormState) {
        composeRule.setContent { PhotosScreen(form, actions, onBack = {}) }
    }

    @Test
    fun showsTheFourLabelledSlots_andHowManyAreCaptured() {
        show(Form1FormState(attachments = mapOf(AttachmentSlot.photo(1) to photo())))

        composeRule.onNodeWithText(text(R.string.photos_subtitle, 1)).assertIsDisplayed()
        val labels = composeRule.activity.resources.getStringArray(R.array.form1_photo_labels)
        labels.forEach { composeRule.onNodeWithText(it).performScrollTo().assertIsDisplayed() }
        (0 until PHOTO_SHOT_COUNT).forEach { composeRule.onNodeWithTag(PhotosTags.tile(it)).performScrollTo().assertIsDisplayed() }
    }

    @Test
    fun tappingASlot_offersTheCameraAndTheGallery() {
        show(Form1FormState())

        composeRule.onNodeWithTag(PhotosTags.tile(0)).performClick()

        composeRule.onNodeWithTag(PhotoSourceTags.SHEET).assertIsDisplayed()
        composeRule.onNodeWithTag(PhotoSourceTags.TAKE).assertIsDisplayed()
        composeRule.onNodeWithTag(PhotoSourceTags.CHOOSE).assertIsDisplayed()
        composeRule.onNodeWithTag(PhotoSourceTags.REMOVE).assertDoesNotExist() // nothing to remove yet
    }

    @Test
    fun aSlotThatHasAPhoto_canHaveItRemoved() {
        show(Form1FormState(attachments = mapOf(AttachmentSlot.photo(2) to photo())))

        composeRule.onNodeWithTag(PhotosTags.tile(2)).performScrollTo().performClick()
        composeRule.onNodeWithTag(PhotoSourceTags.REMOVE).assertIsDisplayed().performClick()

        assertEquals(listOf(AttachmentSlot.photo(2)), removed)
        composeRule.onNodeWithTag(PhotoSourceTags.SHEET).assertDoesNotExist()
    }

    @Test
    fun theTakeNextButton_opensTheFirstEmptySlot() {
        show(Form1FormState(attachments = mapOf(AttachmentSlot.photo(0) to photo())))

        composeRule.onNodeWithTag(PhotosTags.TAKE_NEXT).performScrollTo().performClick()

        // Slot 0 has a photo, so the sheet that opens for the next empty slot has nothing to remove.
        composeRule.onNodeWithTag(PhotoSourceTags.SHEET).assertIsDisplayed()
        composeRule.onNodeWithTag(PhotoSourceTags.REMOVE).assertDoesNotExist()
    }

    @Test
    fun whenEverySlotIsFull_thereIsNothingLeftToTake() {
        show(Form1FormState(attachments = (0 until PHOTO_SHOT_COUNT).associate { AttachmentSlot.photo(it) to photo() }))

        composeRule.onNodeWithTag(PhotosTags.TAKE_NEXT).performScrollTo().assertTextContains(text(R.string.photos_all_taken))
        composeRule.onNodeWithTag(PhotosTags.TAKE_NEXT).performClick()
        composeRule.onNodeWithTag(PhotoSourceTags.SHEET).assertDoesNotExist()
    }
}
