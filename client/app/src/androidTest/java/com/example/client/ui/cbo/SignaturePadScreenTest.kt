package com.example.client.ui.cbo

import android.graphics.BitmapFactory
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.IntSize
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.client.R
import com.example.client.data.local.entity.AttachmentKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The signature pad: sign with a finger, clear, accept; what is accepted is a real picture of what was drawn. */
@RunWith(AndroidJUnit4::class)
class SignaturePadScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val accepted = mutableListOf<ByteArray>()
    private var backs = 0

    private fun show(kind: AttachmentKind = AttachmentKind.DONOR_SIGNATURE, donorName: String = "Jane Donor") {
        composeRule.setContent { SignaturePadScreen(kind, donorName, onAccept = { accepted += it }, onBack = { backs++ }) }
    }

    private fun sign() {
        composeRule.onNodeWithTag(SignatureTags.PAD).performTouchInput {
            swipe(Offset(width * 0.1f, height * 0.7f), Offset(width * 0.5f, height * 0.2f), 200)
            swipe(Offset(width * 0.5f, height * 0.2f), Offset(width * 0.9f, height * 0.7f), 200)
        }
        composeRule.waitForIdle()
    }

    @Test
    fun theDonorsPad_isTitledForTheDonor_andNamesThemAtTheFoot() {
        show(AttachmentKind.DONOR_SIGNATURE, "Jane Donor")

        composeRule.onNodeWithTag(SignatureTags.SCREEN).assertIsDisplayed()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.sign_donor_title)).assertIsDisplayed()
        composeRule.onNodeWithText("Jane Donor", substring = true).assertIsDisplayed()
    }

    @Test
    fun theCbosPad_isTitledForTheCbo() {
        show(AttachmentKind.CBO_SIGNATURE)

        composeRule.onNodeWithText(composeRule.activity.getString(R.string.sign_cbo_title)).assertIsDisplayed()
    }

    @Test
    fun anEmptyPad_cannotBeAccepted() {
        show()

        composeRule.onNodeWithTag(SignatureTags.ACCEPT).performClick()
        composeRule.waitForIdle()

        assertTrue(accepted.isEmpty())
    }

    @Test
    fun aSignatureDrawnWithAFinger_isAccepted_asAPngWithInkOnIt() {
        show()
        sign()

        composeRule.onNodeWithText(composeRule.activity.getString(R.string.sign_pad_captured)).assertIsDisplayed()
        composeRule.onNodeWithTag(SignatureTags.ACCEPT).performClick()
        composeRule.waitUntil(5_000) { accepted.isNotEmpty() }

        val png = accepted.single()
        assertEquals("a PNG starts with its signature bytes", listOf(0x89, 0x50, 0x4E, 0x47), png.take(4).map { it.toInt() and 0xFF })
        val bitmap = BitmapFactory.decodeByteArray(png, 0, png.size)
        assertNotNull(bitmap)
        val pixels = IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }
        assertTrue("the picture is white paper with the signature in ink", pixels.any { it == android.graphics.Color.WHITE } && pixels.any { it != android.graphics.Color.WHITE })
    }

    @Test
    fun clearing_throwsTheSignatureAway() {
        show()
        sign()

        composeRule.onNodeWithTag(SignatureTags.CLEAR).performClick()
        composeRule.onNodeWithTag(SignatureTags.ACCEPT).performClick()
        composeRule.waitForIdle()

        assertTrue(accepted.isEmpty())
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.sign_pad_hint)).assertIsDisplayed()
    }

    @Test
    fun theRenderedPicture_isNeverWiderThanTheLimit_andKeepsTheShapeOfThePad() {
        val strokes = listOf(listOf(Offset(10f, 10f), Offset(1500f, 500f)))

        val png = renderSignaturePng(strokes, IntSize(2000, 700), strokeWidthPx = 6f, maxWidthPx = 800)

        val bitmap = BitmapFactory.decodeByteArray(png, 0, png.size)
        assertEquals(800, bitmap.width)
        assertEquals(280, bitmap.height)
    }

    @Test
    fun aSingleTap_isKeptAsADot() {
        val png = renderSignaturePng(listOf(listOf(Offset(50f, 50f))), IntSize(200, 100), strokeWidthPx = 8f)

        val bitmap = BitmapFactory.decodeByteArray(png, 0, png.size)
        assertTrue(bitmap.getPixel(50, 50) != android.graphics.Color.WHITE)
    }
}
