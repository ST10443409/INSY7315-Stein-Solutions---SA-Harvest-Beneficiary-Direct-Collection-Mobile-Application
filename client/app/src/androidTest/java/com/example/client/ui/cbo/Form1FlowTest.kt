package com.example.client.ui.cbo

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.client.auth.Session
import com.example.client.auth.SessionManager
import com.example.client.auth.TokenStorage
import com.example.client.auth.UserRole
import com.example.client.data.local.entity.CboCollectionEntity
import com.example.client.data.local.entity.ProductLineEntity
import com.example.client.data.local.entity.SyncStatus
import com.example.client.data.repository.CboCollectionRepository
import com.example.client.sync.CboSyncTrigger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drives the real Form 1 screen on a device the way a collector does: fill it in, add a product, sign, photograph,
 * submit. The ViewModel is the real one; only storage, the sync trigger and the session are in-memory.
 */
@RunWith(AndroidJUnit4::class)
class Form1FlowTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private class InMemoryRepository : CboCollectionRepository {
        val saved = mutableListOf<Pair<CboCollectionEntity, List<ProductLineEntity>>>()
        private val all = MutableStateFlow<List<CboCollectionEntity>>(emptyList())

        override suspend fun save(collection: CboCollectionEntity, productLines: List<ProductLineEntity>) {
            saved += collection to productLines
            all.value = all.value + collection
        }

        override fun observeAll(): Flow<List<CboCollectionEntity>> = all
        override fun observeCount(status: SyncStatus): Flow<Int> = all.map { l -> l.count { it.syncStatus == status } }
    }

    private class InMemoryTokenStorage : TokenStorage {
        private var session: Session? = null
        override fun load() = session
        override fun save(session: Session) { this.session = session }
        override fun clear() { session = null }
    }

    private class CountingTrigger : CboSyncTrigger {
        var calls = 0
        override fun syncCboCollectionsNow() { calls++ }
    }

    private lateinit var repository: InMemoryRepository
    private lateinit var trigger: CountingTrigger

    @Before
    fun launch() {
        repository = InMemoryRepository()
        trigger = CountingTrigger()
        val session = SessionManager(InMemoryTokenStorage()).apply { startSession("jwt", UserRole.CBO_COLLECTION, "cbo-7") }
        val viewModel = Form1ViewModel(repository, trigger, session)
        composeRule.setContent { Form1Route(viewModel) }
    }

    private fun tap(tag: String) = composeRule.onNodeWithTag(tag).performScrollTo().performClick()

    private fun fillValidForm() {
        composeRule.onNodeWithTag(Form1Tags.DONOR_NAME).performScrollTo().performTextInput("Jane Donor")
        tap(Form1Tags.ADD_PRODUCT)
        composeRule.onNodeWithTag(Form1Tags.DRAFT_KG).performTextInput("42.5")
        composeRule.onNodeWithTag(Form1Tags.DRAFT_CONFIRM).performClick()
        tap(Form1Tags.SIGN_DONOR)
        tap(Form1Tags.SIGN_CBO)
        tap(Form1Tags.PHOTO_PREFIX + 0)
    }

    @Test
    fun aCompletedForm_isSavedLocally_underTheCollectorsCbo_andShowsTheSuccessState() {
        fillValidForm()
        tap(Form1Tags.SUBMIT)

        composeRule.waitForIdle()
        composeRule.onNodeWithTag(Form1Tags.SUCCESS).assertIsDisplayed()

        val (collection, lines) = repository.saved.single()
        assertEquals("Jane Donor", collection.donorName)
        assertEquals("cbo-7", collection.cboId)
        assertEquals(SyncStatus.PENDING, collection.syncStatus)
        assertEquals("42.5", lines.single().kg)
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
        assertEquals(0, trigger.calls)
    }

    @Test
    fun afterTheSuccessState_theCollectorCanStartAFreshForm() {
        fillValidForm()
        tap(Form1Tags.SUBMIT)
        composeRule.waitForIdle()

        tap(Form1Tags.NEW_COLLECTION)
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(Form1Tags.SUBMIT).performScrollTo().assertIsDisplayed()
        assertEquals(1, repository.saved.size)
    }
}
