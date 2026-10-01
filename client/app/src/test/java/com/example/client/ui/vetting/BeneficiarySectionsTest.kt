package com.example.client.ui.vetting

import com.example.client.data.local.entity.FoodspaceBeneficiaryRecord
import com.example.client.testing.sampleRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * Proves the Vetting detail screen shows every field of the Foodspace application form, in the right sections:
 * once against the form's field list, and once against the Room entity so a field added later cannot be forgotten.
 */
class BeneficiarySectionsTest {

    /** The Foodspace field list the officers' form must contain: section id to the keys in it, in order. */
    private val expectedSheet: Map<String, List<String>> = linkedMapOf(
        "organisation" to listOf("legal", "contact", "email", "phone", "web"),
        "location" to listOf("addr", "addr2", "prov", "w3w"),
        "programmes" to listOf("focus", "target", "progs", "chan"),
        "staffing" to listOf("ftf", "ftm", "vol"),
        "registration" to listOf("npo", "npoDoc", "dsd", "pboDoc"),
        "demographics" to listOf("race", "gender", "age"),
        "feeding" to listOf(
            "freq", "served", "servedF", "servedM", "sAfr", "sCol", "sInd", "sWhi", "rely", "trans", "meals", "days", "lastFed"
        ),
        "facilities" to listOf("storage", "kitchenPix", "clean", "water", "toilets", "pest", "infra"),
        "access" to listOf("access", "parking", "police"),
        "capacity" to listOf("comments", "proposal", "digital", "facPix"),
        "documents" to listOf("sla", "consent", "policy", "certs")
    )

    private val allFields = BENEFICIARY_SECTIONS.flatMap { it.fields }

    @Test
    fun thereAreElevenSections_inTheFormsOrder() {
        assertEquals(expectedSheet.keys.toList(), BENEFICIARY_SECTIONS.map { it.id })
    }

    @Test
    fun eachSectionHoldsExactlyTheFieldsTheFormPutsInIt_inOrder() {
        BENEFICIARY_SECTIONS.forEach { section ->
            assertEquals("fields of ${section.id}", expectedSheet.getValue(section.id), section.fields.map { it.sheetKey })
        }
    }

    @Test
    fun allFiftyFourFormFieldsArePresent_eachExactlyOnce() {
        assertEquals(54, expectedSheet.values.sumOf { it.size })
        assertEquals(54, allFields.size)
        assertEquals(expectedSheet.values.flatten().toSet(), allFields.map { it.sheetKey }.toSet())
        assertEquals(allFields.size, allFields.map { it.sheetKey }.toSet().size)
    }

    @Test
    fun everyPropertyOfTheEntity_isShownOnce_exceptItsId() {
        val entityProperties = FoodspaceBeneficiaryRecord::class.java.declaredFields
            .filter { !it.isSynthetic && !Modifier.isStatic(it.modifiers) }
            .map { it.name }
            .toSet()

        assertTrue("sanity: the entity was read", entityProperties.size > 50)
        val shown = allFields.map { it.entityField }
        assertEquals("a field is shown twice", shown.size, shown.toSet().size)
        assertEquals(
            "Add new entity properties to BENEFICIARY_SECTIONS (or exclude them on purpose here).",
            entityProperties - "id", shown.toSet()
        )
    }

    @Test
    fun everyFieldAndSection_hasItsOwnLabel() {
        val labels = allFields.map { it.label }
        assertTrue(labels.all { it != 0 })
        assertEquals("two fields share a label", labels.size, labels.toSet().size)
        val titles = BENEFICIARY_SECTIONS.map { it.title }
        assertEquals(titles.size, titles.toSet().size)
    }

    @Test
    fun everyFieldReadsItsOwnPropertyOfTheRecord() {
        // Change one property at a time: only the field that claims that property may change.
        val base = sampleRecord()
        fun values(r: FoodspaceBeneficiaryRecord) = allFields.associate { it.sheetKey to it.read(r) }
        val baseValues = values(base)

        val changes: Map<String, FoodspaceBeneficiaryRecord> = mapOf(
            "legalName" to base.copy(legalName = "X"),
            "contactEmail" to base.copy(contactEmail = "x@y.z"),
            "website" to base.copy(website = null),
            "address2" to base.copy(address2 = "X"),
            "province" to base.copy(province = "X"),
            "coreBusiness" to base.copy(coreBusiness = "X"),
            "targetPopulation" to base.copy(targetPopulation = listOf("X")),
            "volunteers" to base.copy(volunteers = 99),
            "registeredDsd" to base.copy(registeredDsd = !base.registeredDsd),
            "pboCertificate" to base.copy(pboCertificate = "X"),
            "ageGroups" to base.copy(ageGroups = listOf("X")),
            "whiteServed" to base.copy(whiteServed = 99),
            "lastDateFed" to base.copy(lastDateFed = null),
            "kitchenImages" to base.copy(kitchenImages = null),
            "pestFree" to base.copy(pestFree = !base.pestFree),
            "infrastructureChecks" to base.copy(infrastructureChecks = emptyList()),
            "policeProximity" to base.copy(policeProximity = null),
            "facilityPhotos" to base.copy(facilityPhotos = "X"),
            "hasPolicy" to base.copy(hasPolicy = !base.hasPolicy),
            "certificates" to base.copy(certificates = null)
        )
        changes.forEach { (property, changed) ->
            val moved = values(changed).filter { (key, v) -> v != baseValues.getValue(key) }.keys
            val owner = allFields.single { it.entityField == property }.sheetKey
            assertEquals("changing $property", setOf(owner), moved)
        }
    }

    @Test
    fun theKindOfEachField_followsTheFormsDataType() {
        fun kind(key: String) = allFields.single { it.sheetKey == key }.kind
        assertEquals(FieldKind.ADDRESS, kind("addr"))
        assertEquals(FieldKind.MULTILINE, kind("addr2"))
        assertEquals(FieldKind.WHAT3WORDS, kind("w3w"))
        assertEquals(FieldKind.CHIPS, kind("target"))
        assertEquals(FieldKind.NUMBER, kind("ftf"))
        assertEquals(FieldKind.YES_NO, kind("npo"))
        assertEquals(FieldKind.DATE, kind("lastFed"))
        assertEquals(FieldKind.FILE, kind("certs"))
        // Every uploaded-file field of the form is a FILE.
        assertEquals(setOf("npoDoc", "pboDoc", "kitchenPix", "facPix", "certs"), allFields.filter { it.kind == FieldKind.FILE }.map { it.sheetKey }.toSet())
        // ...and every Yes/No of the form is a YES_NO.
        assertEquals(
            setOf("npo", "dsd", "clean", "water", "toilets", "pest", "access", "parking", "sla", "consent", "policy"),
            allFields.filter { it.kind == FieldKind.YES_NO }.map { it.sheetKey }.toSet()
        )
    }

    @Test
    fun valuesAreReadInTheirOwnShape() {
        val r = sampleRecord()
        fun value(key: String) = allFields.single { it.sheetKey == key }.read(r)

        assertEquals(FieldValue.Text("Sizanani Community Feeding NPO"), value("legal"))
        assertEquals(FieldValue.Chips(listOf("Children", "Elderly")), value("target"))
        assertEquals(FieldValue.Number(12), value("vol"))
        assertEquals(FieldValue.YesNo(true), value("npo"))
        assertEquals(FieldValue.YesNo(false), value("dsd"))
        assertEquals(FieldValue.Date(1_790_000_000_000L), value("lastFed"))
        assertEquals(FieldValue.File("https://files.example.org/npo-123.pdf"), value("npoDoc"))
        assertEquals(FieldValue.File(null), value("pboDoc"))
        assertNotEquals(value("served"), value("servedF"))
    }
}
