package com.example.client.ui.vetting

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.client.R
import com.example.client.data.local.entity.DecisionOutcome
import com.example.client.testing.sampleDecision
import com.example.client.testing.sampleRecord
import com.example.client.ui.components.AccordionTags
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The beneficiary detail screen: all 54 fields, in 11 sections that expand and retract. */
@RunWith(AndroidJUnit4::class)
class BeneficiaryDetailScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun text(id: Int) = composeRule.activity.getString(id)

    private fun show(
        state: DetailUiState = DetailUiState.Ready(sampleRecord(), null),
        onBack: () -> Unit = {},
        onRecordDecision: () -> Unit = {}
    ) {
        composeRule.setContent { BeneficiaryDetailScreen(state, onBack, onRecordDecision) }
    }

    private fun header(id: String) = composeRule.onNodeWithTag(AccordionTags.header(id))
    private fun body(id: String) = composeRule.onNodeWithTag(AccordionTags.body(id))

    private fun expandAll() {
        composeRule.onNodeWithTag(DetailTags.EXPAND_ALL).performClick()
    }

    // ── the sections ───────────────────────────────────────────────────────────────

    @Test
    fun allElevenSections_areListed_withTheirTitlesAndFieldCounts() {
        show()

        assertEquals(11, BENEFICIARY_SECTIONS.size)
        BENEFICIARY_SECTIONS.forEach { section ->
            header(section.id).performScrollTo().assertIsDisplayed()
            header(section.id).assertTextContains(text(section.title))
            val count = composeRule.activity.resources.getQuantityString(R.plurals.vetting_field_count, section.fields.size, section.fields.size)
            header(section.id).assertTextContains(count)
        }
    }

    @Test
    fun theFirstSection_startsOpen_andTheRestAreClosed() {
        show()

        body("organisation").assertExists()
        BENEFICIARY_SECTIONS.drop(1).forEach { body(it.id).assertDoesNotExist() }
    }

    @Test
    fun aSection_opensAndRetractsWhenItsHeaderIsTapped() {
        show()

        header("location").performScrollTo().performClick()
        body("location").assertExists()
        composeRule.onNodeWithTag(DetailTags.field("addr")).performScrollTo().assertIsDisplayed()

        header("location").performScrollTo().performClick()
        composeRule.waitForIdle()
        body("location").assertDoesNotExist()
        composeRule.onNodeWithTag(DetailTags.field("addr")).assertDoesNotExist()
    }

    @Test
    fun severalSections_canBeOpenAtOnce() {
        show()

        header("location").performScrollTo().performClick()
        header("staffing").performScrollTo().performClick()

        body("organisation").assertExists()
        body("location").assertExists()
        body("staffing").assertExists()
        body("feeding").assertDoesNotExist()
    }

    @Test
    fun theHeader_announcesWhetherItIsOpenOrClosed() {
        show()

        header("organisation").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, text(R.string.accordion_expanded)))
        header("location").performScrollTo()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, text(R.string.accordion_collapsed)))
        header("location").performClick()
        header("location").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, text(R.string.accordion_expanded)))
    }

    @Test
    fun expandAll_opensEverySection_andThenOffersCollapseAll() {
        show()

        expandAll()

        BENEFICIARY_SECTIONS.forEach { body(it.id).assertExists() }
        composeRule.onNodeWithTag(DetailTags.COLLAPSE_ALL).assertIsDisplayed()
        composeRule.onNodeWithTag(DetailTags.EXPAND_ALL).assertDoesNotExist()

        composeRule.onNodeWithTag(DetailTags.COLLAPSE_ALL).performClick()
        composeRule.waitForIdle()
        BENEFICIARY_SECTIONS.forEach { body(it.id).assertDoesNotExist() }
        composeRule.onNodeWithTag(DetailTags.EXPAND_ALL).assertIsDisplayed()
    }

    @Test
    fun whichSectionsAreOpen_survivesARotation() {
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent { BeneficiaryDetailScreen(DetailUiState.Ready(sampleRecord(), null), {}, {}) }
        header("staffing").performScrollTo().performClick()
        body("staffing").assertExists()

        restoration.emulateSavedInstanceStateRestore()

        body("staffing").assertExists()
        body("organisation").assertExists()
        body("location").assertDoesNotExist()
    }

    // ── every field ────────────────────────────────────────────────────────────────

    @Test
    fun allFiftyFourFields_areShown_eachWithItsLabelFromTheForm() {
        show()
        expandAll()

        val fields = BENEFICIARY_SECTIONS.flatMap { it.fields }
        assertEquals(54, fields.size)
        fields.forEach { spec ->
            val row = composeRule.onNodeWithTag(DetailTags.field(spec.sheetKey))
            row.performScrollTo().assertIsDisplayed()
            row.assertTextContains(text(spec.label))
        }
    }

    @Test
    fun valuesAreShown_theWayTheirTypeCallsFor() {
        show()
        expandAll()
        fun field(key: String) = composeRule.onNodeWithTag(DetailTags.field(key)).performScrollTo()

        field("legal").assertTextContains("Sizanani Community Feeding NPO")
        field("w3w").assertTextContains("///filled.count.soap")
        field("target").assertTextContains("Children")
        field("target").assertTextContains("Elderly")
        field("vol").assertTextContains("12")
        field("npo").assertTextContains(text(R.string.vetting_yes))
        field("dsd").assertTextContains(text(R.string.vetting_no))
        field("lastFed").assertTextContains("2026/09/21")
        field("npoDoc").assertTextContains(text(R.string.vetting_file_attached))
        composeRule.onNodeWithText(text(R.string.vetting_open_maps)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun missingValues_sayWhatIsMissing_insteadOfShowingABlank() {
        show(DetailUiState.Ready(sampleRecord().copy(website = null, pboCertificate = null, lastDateFed = null, race = emptyList(), proposalWriting = ""), null))
        expandAll()
        fun field(key: String) = composeRule.onNodeWithTag(DetailTags.field(key)).performScrollTo()

        field("web").assertTextContains(text(R.string.vetting_not_provided))
        field("proposal").assertTextContains(text(R.string.vetting_not_provided))
        field("pboDoc").assertTextContains(text(R.string.vetting_no_file))
        field("lastFed").assertTextContains(text(R.string.vetting_not_recorded))
        field("race").assertTextContains(text(R.string.vetting_none_selected))
    }

    // ── decision summary and entry point ───────────────────────────────────────────

    @Test
    fun withoutADecision_theScreenSaysSo_andOffersToRecordOne() {
        var tapped = 0
        show(onRecordDecision = { tapped++ })

        composeRule.onNodeWithTag(DetailTags.CURRENT_DECISION).assertTextContains(text(R.string.vetting_no_decision_yet))
        composeRule.onNodeWithTag(DetailTags.RECORD_DECISION).assertTextContains(text(R.string.vetting_record_decision))
        composeRule.onNodeWithTag(DetailTags.RECORD_DECISION).performClick()

        assertEquals(1, tapped)
    }

    @Test
    fun withADecision_itIsShown_andTheButtonOffersToChangeIt() {
        show(DetailUiState.Ready(sampleRecord(), sampleDecision(outcome = DecisionOutcome.REJECT)))

        composeRule.onNodeWithTag(DetailTags.CURRENT_DECISION).assertTextContains(text(R.string.vetting_outcome_reject))
        composeRule.onNodeWithTag(DetailTags.RECORD_DECISION).assertTextContains(text(R.string.vetting_change_decision))
    }

    @Test
    fun theDecisionButton_isReachableWhateverTheScrollPosition() {
        show()
        expandAll()
        composeRule.onNodeWithTag(DetailTags.field("certs")).performScrollTo() // the very bottom

        composeRule.onNodeWithTag(DetailTags.RECORD_DECISION).assertIsDisplayed()
    }

    // ── other states ───────────────────────────────────────────────────────────────

    @Test
    fun aRecordThatIsNoLongerOnTheDevice_isExplained_notBlank() {
        show(DetailUiState.NotFound)

        composeRule.onNodeWithTag(DetailTags.NOT_FOUND).assertTextContains(text(R.string.vetting_detail_not_found))
    }

    @Test
    fun whileLoading_nothingIsAnnouncedAsMissing() {
        show(DetailUiState.Loading)

        composeRule.onNodeWithTag(DetailTags.NOT_FOUND).assertDoesNotExist()
        composeRule.onNodeWithTag(DetailTags.SCREEN).assertIsDisplayed()
    }

    @Test
    fun theRecordsNameAndProvince_headTheScreen() {
        show()

        // The name also appears as the first field of the open section, so look for the header copy.
        composeRule.onAllNodesWithText("Sizanani Community Feeding NPO").onFirst().assertIsDisplayed()
        composeRule.onAllNodesWithText("Gauteng").onFirst().assertIsDisplayed()
    }
}
