package com.example.client.ui.cbo

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.client.R
import com.example.client.auth.Session
import com.example.client.auth.SessionManager
import com.example.client.auth.TokenStorage
import com.example.client.auth.UserRole
import com.example.client.data.local.entity.AttachmentKind
import com.example.client.data.local.entity.SyncStatus
import com.example.client.testing.CountingCboSyncTrigger
import com.example.client.testing.FakeAttachmentStorage
import com.example.client.testing.InMemoryCboCollectionRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drives the real Form 1 screen on a device the way a collector does: fill it in, add a product, sign, photograph,
 * submit. The ViewModel is the real one; only storage, the sync trigger and the session are in-memory. The signature pad
 * and the photos screen are separate screens (see their own tests), so here a tap on them is checked for where it leads
 * and the picture arriving is done through the ViewModel, exactly as those screens do it.
 */
@RunWith(AndroidJUnit4::class)
class Form1FlowTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private class InMemoryTokenStorage : TokenStorage {
        private var session: Session? = null
        override fun load() = session
        override fun save(session: Session) { this.session = session }
        override fun clear() { session = null }
    }

    private lateinit var repository: InMemoryCboCollectionRepository
    private lateinit var trigger: CountingCboSyncTrigger
    private lateinit var viewModel: Form1ViewModel
    private val opened = mutableListOf<AttachmentKind>()
    private var photosOpened = 0
    private var submitted = 0

    @Before
    fun launch() {
        repository = InMemoryCboCollectionRepository()
        trigger = CountingCboSyncTrigger()
        val session = SessionManager(InMemoryTokenStorage()).apply { startSession("jwt", UserRole.CBO_COLLECTION, "cbo-7") }
        viewModel = Form1ViewModel(repository, trigger, session, FakeAttachmentStorage())
        composeRule.setContent {
            Form1Route(
                onOpenSignature = { opened += it },
                onOpenPhotos = { photosOpened++ },
                onSubmitted = { submitted++ },
                viewModel = viewModel
            )
        }
    }

    private fun tap(tag: String) = composeRule.onNodeWithTag(tag).performScrollTo().performClick()

    private fun text(id: Int) = composeRule.activity.getString(id)

    /** What the signature pad and the photos screen do when the user accepts a signature or takes a photo. */
    private fun attachPictures() {
        composeRule.runOnUiThread {
            viewModel.onSignatureCaptured(AttachmentKind.DONOR_SIGNATURE, byteArrayOf(1))
            viewModel.onSignatureCaptured(AttachmentKind.CBO_SIGNATURE, byteArrayOf(2))
            viewModel.onPhotoPicked(AttachmentSlot.photo(0), "content://gallery/1")
        }
        composeRule.waitForIdle()
    }

    private fun fillValidForm() {
        composeRule.onNodeWithTag(Form1Tags.DONOR_NAME).performScrollTo().performTextInput("Jane Donor")
        tap(Form1Tags.ADD_PRODUCT)
        composeRule.onNodeWithTag(Form1Tags.DRAFT_KG).performTextInput("42.5")
        composeRule.onNodeWithTag(Form1Tags.DRAFT_CONFIRM).performClick()
        attachPictures()
    }

    @Test
    fun tappingASignatureButton_opensThePadForThatSignature() {
        tap(Form1Tags.SIGN_DONOR)
        tap(Form1Tags.SIGN_CBO)

        assertEquals(listOf(AttachmentKind.DONOR_SIGNATURE, AttachmentKind.CBO_SIGNATURE), opened)
    }

    @Test
    fun tappingThePhotosRow_opensThePhotosScreen() {
        tap(Form1Tags.PHOTOS_ROW)

        assertEquals(1, photosOpened)
    }

    @Test
    fun aCapturedSignatureAndPhoto_showUpOnTheForm() {
        composeRule.onNodeWithTag(Form1Tags.SIGN_DONOR).performScrollTo().assertTextContains(text(R.string.form1_tap_to_sign), substring = true)

        attachPictures()

        composeRule.onNodeWithTag(Form1Tags.SIGN_DONOR).performScrollTo().assertTextContains(text(R.string.form1_signed), substring = true)
        composeRule.onNodeWithTag(Form1Tags.SIGN_CBO).performScrollTo().assertTextContains(text(R.string.form1_signed), substring = true)
        composeRule.onNodeWithTag(Form1Tags.PHOTOS_ROW).performScrollTo()
            .assertTextContains(composeRule.activity.getString(R.string.form1_photos_row_count, 1), substring = true)
    }

    @Test
    fun aCompletedForm_isSavedLocally_withItsPictures_andHandsOverToTheReceipt() {
        fillValidForm()
        tap(Form1Tags.SUBMIT)

        composeRule.waitForIdle()
        assertEquals(1, submitted)

        val (collection, lines, attachments) = repository.saved.single()
        assertEquals("Jane Donor", collection.donorName)
        assertEquals("cbo-7", collection.cboId)
        assertEquals(SyncStatus.PENDING, collection.syncStatus)
        assertEquals("42.5", lines.single().kg)
        assertEquals(3, attachments.size)
        assertTrue(attachments.all { it.collectionId == collection.id })
        assertEquals(1, trigger.calls)
    }

    @Test
    fun submittingAnEmptyForm_savesNothing_andExplainsWhatIsMissing() {
        tap(Form1Tags.SUBMIT)

        composeRule.waitForIdle()
        composeRule.onNodeWithTag(Form1Tags.error(Form1Field.DONOR_NAME)).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(Form1Tags.error(Form1Field.PRODUCTS)).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(Form1Tags.error(Form1Field.SIGNATURES)).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(Form1Tags.error(Form1Field.PHOTOS)).performScrollTo().assertIsDisplayed()
        assertTrue(repository.saved.isEmpty())
        assertEquals(0, submitted)
        assertEquals(0, trigger.calls)
    }

    @Test
    fun afterTheReceipt_theCollectorCanStartAFreshForm() {
        fillValidForm()
        tap(Form1Tags.SUBMIT)
        composeRule.waitForIdle()

        composeRule.runOnUiThread { viewModel.onStartNew() }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(Form1Tags.SUBMIT).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(Form1Tags.SIGN_DONOR).performScrollTo().assertTextContains(text(R.string.form1_tap_to_sign), substring = true)
        assertEquals(1, repository.saved.size)
    }
}
