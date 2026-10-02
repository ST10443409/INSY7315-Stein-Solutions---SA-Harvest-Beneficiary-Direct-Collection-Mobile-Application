package com.example.client.ui.vetting

import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.SavedStateHandle
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.client.R
import com.example.client.auth.SessionManager
import com.example.client.auth.UserRole
import com.example.client.data.local.entity.DecisionOutcome
import com.example.client.data.repository.RefreshOutcome
import com.example.client.testing.FakeVettingRecordsRepository
import com.example.client.testing.FakeVettingRepository
import com.example.client.testing.FakeVettingSyncTrigger
import com.example.client.testing.InMemoryTokenStorage
import com.example.client.testing.sampleRecord
import com.example.client.ui.components.AccordionTags
import com.example.client.ui.navigation.AppNavHost
import com.example.client.ui.navigation.VettingScreens
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A vetting officer's whole journey through the real navigation graph and the real ViewModels, with the device
 * offline the whole time: list, open a record, read it, record a decision, and see it back in the list.
 */
@RunWith(AndroidJUnit4::class)
class VettingFlowTest {

    @get:org.junit.Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun text(id: Int) = composeRule.activity.getString(id)

    private val records = FakeVettingRecordsRepository(
        listOf(sampleRecord("a", legalName = "Alpha NPO"), sampleRecord("b", legalName = "Beta Kitchen"), sampleRecord("c", legalName = "Gamma Hope"))
    ).apply { outcome = RefreshOutcome.OFFLINE } // no connection for the entire test
    private val vetting = FakeVettingRepository()

    private fun launch(role: UserRole = UserRole.VETTING) {
        val session = SessionManager(InMemoryTokenStorage()).apply { startSession("jwt", role, null, "vetting_test_user") }
        composeRule.setContent {
            val navController = rememberNavController()
            var selected by remember { mutableStateOf("") }
            AppNavHost(
                role, navController,
                vetting = VettingScreens(
                    list = { onOpen ->
                        val vm = remember { VettingListViewModel(records, vetting, com.example.client.auth.SessionManager(com.example.client.testing.InMemoryTokenStorage())) }
                        val state by vm.uiState.collectAsState()
                        VettingListScreen(state, vm::refresh, onOpen = { id -> selected = id; onOpen(id) })
                    },
                    detail = { onBack, onDecide ->
                        val vm = remember(selected) { BeneficiaryDetailViewModel(SavedStateHandle(mapOf(VETTING_RECORD_ARG to selected)), records, vetting) }
                        val state by vm.uiState.collectAsState()
                        BeneficiaryDetailScreen(state, onBack, onRecordDecision = { onDecide(selected) })
                    },
                    decision = { onBack ->
                        val vm = remember(selected) { DecisionViewModel(SavedStateHandle(mapOf(VETTING_RECORD_ARG to selected)), records, vetting, session, FakeVettingSyncTrigger()) }
                        val state by vm.uiState.collectAsState()
                        DecisionScreen(state, onBack, vm::onOutcomeChange, vm::onNotesChange, vm::onSave)
                    }
                ),
                // The Admin's own screens are not under test here; the Overview needs Hilt, so it is left empty.
                admin = com.example.client.ui.navigation.AdminScreens(overview = { })
            )
        }
    }

    private fun pressBack() {
        composeRule.runOnUiThread {
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        composeRule.waitForIdle()
    }

    @Test
    fun fromTheListToADecisionAndBack_withoutAnyConnection() {
        launch()

        // 1. The list shows the cached records and says we are offline, without blocking anything.
        composeRule.onNodeWithTag(ListTags.NOTICE).assertTextContains(text(R.string.vetting_notice_offline))
        listOf("a", "b", "c").forEach { composeRule.onNodeWithTag(ListTags.item(it)).assertIsDisplayed() }

        // 2. Open a record and read it.
        composeRule.onNodeWithTag(ListTags.item("b")).performClick()
        composeRule.onNodeWithTag(DetailTags.SCREEN).assertIsDisplayed()
        composeRule.onAllNodesWithText("Beta Kitchen").onFirst().assertIsDisplayed()
        composeRule.onNodeWithTag(DetailTags.EXPAND_ALL).performClick()
        composeRule.onNodeWithTag(AccordionTags.header("documents")).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(DetailTags.field("certs")).performScrollTo().assertIsDisplayed()

        // 3. Record a decision on it.
        composeRule.onNodeWithTag(DetailTags.RECORD_DECISION).performClick()
        composeRule.onNodeWithTag(DecisionTags.SCREEN).assertIsDisplayed()
        composeRule.onNodeWithTag(DecisionTags.option(DecisionOutcome.FLAG)).performClick()
        composeRule.onNodeWithTag(DecisionTags.NOTES).performScrollTo().performTextInput("Check the kitchen photos")
        composeRule.onNodeWithTag(DecisionTags.SAVE).performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(DecisionTags.SAVED).assertIsDisplayed()

        // 4. Back to the record: the decision is now its current one, and the button offers to change it.
        composeRule.onNodeWithTag(DecisionTags.DONE).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(DetailTags.CURRENT_DECISION).assertTextContains(text(R.string.vetting_outcome_flag))
        composeRule.onNodeWithTag(DetailTags.RECORD_DECISION).assertTextContains(text(R.string.vetting_change_decision))

        // 5. Back to the list: the record carries its decision and the summary counts it.
        pressBack()
        composeRule.onNodeWithTag(ListTags.item("b")).assertTextContains(text(R.string.vetting_outcome_flag))
        composeRule.onNodeWithText("3 records · 1 decided", substring = true).assertIsDisplayed()

        // The decision exists only on the device, waiting to sync.
        val saved = vetting.decisions.value.single()
        assertEquals("b", saved.foodspaceRecordId)
        assertEquals("Check the kitchen photos", saved.notes)
        assertEquals("vetting_test_user", saved.officerId)
    }

    @Test
    fun anAdmin_reachesTheSameThreeScreens() {
        launch(UserRole.ADMIN)

        composeRule.onNodeWithTag(com.example.client.ui.components.bottomNavTag(com.example.client.ui.navigation.Routes.ADMIN_FORM2)).performClick()
        composeRule.onNodeWithTag(ListTags.item("a")).performClick()
        composeRule.onNodeWithTag(DetailTags.SCREEN).assertIsDisplayed()
        composeRule.onNodeWithTag(DetailTags.RECORD_DECISION).performClick()
        composeRule.onNodeWithTag(DecisionTags.SCREEN).assertIsDisplayed()
    }

    @Test
    fun backFromARecord_returnsToTheList() {
        launch()
        composeRule.onNodeWithTag(ListTags.item("a")).performClick()
        composeRule.onNodeWithTag(DetailTags.SCREEN).assertIsDisplayed()

        pressBack()

        composeRule.onNodeWithTag(ListTags.SCREEN).assertIsDisplayed()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }
}
