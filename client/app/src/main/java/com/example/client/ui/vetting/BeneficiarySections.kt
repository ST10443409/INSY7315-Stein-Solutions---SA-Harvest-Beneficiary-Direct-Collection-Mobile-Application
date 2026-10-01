package com.example.client.ui.vetting

import androidx.annotation.StringRes
import com.example.client.R
import com.example.client.data.local.entity.FoodspaceBeneficiaryRecord

/** How a field's value is shown. Mirrors the "data type" column of the Foodspace application form. */
enum class FieldKind { TEXT, MULTILINE, EMAIL, PHONE, URL, ADDRESS, WHAT3WORDS, NUMBER, YES_NO, CHIPS, DATE, FILE }

/** A field's value, already read off the record, ready to be shown. */
sealed interface FieldValue {
    data class Text(val text: String?) : FieldValue
    data class Chips(val values: List<String>) : FieldValue
    data class YesNo(val value: Boolean) : FieldValue
    data class Number(val value: Int) : FieldValue
    data class Date(val epochMillis: Long?) : FieldValue

    /** A reference to an uploaded file (a link or an id; Foodspace has not said which). Null when nothing was uploaded. */
    data class File(val reference: String?) : FieldValue
}

/**
 * One field of the beneficiary record.
 * @param sheetKey the key in the Foodspace field list this came from (`legal`, `npoDoc`, ...), for traceability.
 * @param entityField the [FoodspaceBeneficiaryRecord] property it reads, so a test can prove every property is shown.
 */
class FieldSpec(
    val sheetKey: String,
    val entityField: String,
    @StringRes val label: Int,
    val kind: FieldKind,
    val read: (FoodspaceBeneficiaryRecord) -> FieldValue
)

/** One accordion section: a title and the fields inside it, in display order. */
class SectionSpec(val id: String, @StringRes val title: Int, val fields: List<FieldSpec>)

private fun text(key: String, field: String, label: Int, kind: FieldKind = FieldKind.TEXT, read: (FoodspaceBeneficiaryRecord) -> String?) =
    FieldSpec(key, field, label, kind) { FieldValue.Text(read(it)) }

private fun number(key: String, field: String, label: Int, read: (FoodspaceBeneficiaryRecord) -> Int) =
    FieldSpec(key, field, label, FieldKind.NUMBER) { FieldValue.Number(read(it)) }

private fun yesNo(key: String, field: String, label: Int, read: (FoodspaceBeneficiaryRecord) -> Boolean) =
    FieldSpec(key, field, label, FieldKind.YES_NO) { FieldValue.YesNo(read(it)) }

private fun chips(key: String, field: String, label: Int, read: (FoodspaceBeneficiaryRecord) -> List<String>) =
    FieldSpec(key, field, label, FieldKind.CHIPS) { FieldValue.Chips(read(it)) }

private fun file(key: String, field: String, label: Int, read: (FoodspaceBeneficiaryRecord) -> String?) =
    FieldSpec(key, field, label, FieldKind.FILE) { FieldValue.File(read(it)) }

/**
 * Every field of a beneficiary record, grouped into the sections the Vetting detail screen shows as accordions.
 * 11 sections, 54 fields: exactly the Foodspace application field list. The record's `id` is the only property
 * not shown (it is a key, not something an officer reviews); a unit test fails if a property is added to the
 * entity and not added here.
 */
val BENEFICIARY_SECTIONS: List<SectionSpec> = listOf(
    SectionSpec(
        "organisation", R.string.vetting_section_organisation,
        listOf(
            text("legal", "legalName", R.string.vetting_field_legal) { it.legalName },
            text("contact", "contactName", R.string.vetting_field_contact) { it.contactName },
            text("email", "contactEmail", R.string.vetting_field_email, FieldKind.EMAIL) { it.contactEmail },
            text("phone", "contactPhone", R.string.vetting_field_phone, FieldKind.PHONE) { it.contactPhone },
            text("web", "website", R.string.vetting_field_web, FieldKind.URL) { it.website }
        )
    ),
    SectionSpec(
        "location", R.string.vetting_section_location,
        listOf(
            text("addr", "address", R.string.vetting_field_addr, FieldKind.ADDRESS) { it.address },
            text("addr2", "address2", R.string.vetting_field_addr2, FieldKind.MULTILINE) { it.address2 },
            text("prov", "province", R.string.vetting_field_prov) { it.province },
            text("w3w", "what3words", R.string.vetting_field_w3w, FieldKind.WHAT3WORDS) { it.what3words }
        )
    ),
    SectionSpec(
        "programmes", R.string.vetting_section_programmes,
        listOf(
            text("focus", "coreBusiness", R.string.vetting_field_focus) { it.coreBusiness },
            chips("target", "targetPopulation", R.string.vetting_field_target) { it.targetPopulation },
            text("progs", "programmes", R.string.vetting_field_progs, FieldKind.MULTILINE) { it.programmes },
            text("chan", "distributionChannel", R.string.vetting_field_chan) { it.distributionChannel }
        )
    ),
    SectionSpec(
        "staffing", R.string.vetting_section_staffing,
        listOf(
            number("ftf", "fullTimeFemales", R.string.vetting_field_ftf) { it.fullTimeFemales },
            number("ftm", "fullTimeMales", R.string.vetting_field_ftm) { it.fullTimeMales },
            number("vol", "volunteers", R.string.vetting_field_vol) { it.volunteers }
        )
    ),
    SectionSpec(
        "registration", R.string.vetting_section_registration,
        listOf(
            yesNo("npo", "registeredNpo", R.string.vetting_field_npo) { it.registeredNpo },
            file("npoDoc", "npoCertificate", R.string.vetting_field_npoDoc) { it.npoCertificate },
            yesNo("dsd", "registeredDsd", R.string.vetting_field_dsd) { it.registeredDsd },
            file("pboDoc", "pboCertificate", R.string.vetting_field_pboDoc) { it.pboCertificate }
        )
    ),
    SectionSpec(
        "demographics", R.string.vetting_section_demographics,
        listOf(
            chips("race", "race", R.string.vetting_field_race) { it.race },
            chips("gender", "gender", R.string.vetting_field_gender) { it.gender },
            chips("age", "ageGroups", R.string.vetting_field_age) { it.ageGroups }
        )
    ),
    SectionSpec(
        "feeding", R.string.vetting_section_feeding,
        listOf(
            text("freq", "feedingFrequency", R.string.vetting_field_freq) { it.feedingFrequency },
            number("served", "totalServed", R.string.vetting_field_served) { it.totalServed },
            number("servedF", "femalesServed", R.string.vetting_field_servedF) { it.femalesServed },
            number("servedM", "malesServed", R.string.vetting_field_servedM) { it.malesServed },
            number("sAfr", "africanServed", R.string.vetting_field_sAfr) { it.africanServed },
            number("sCol", "colouredServed", R.string.vetting_field_sCol) { it.colouredServed },
            number("sInd", "indianServed", R.string.vetting_field_sInd) { it.indianServed },
            number("sWhi", "whiteServed", R.string.vetting_field_sWhi) { it.whiteServed },
            text("rely", "relianceOnSaHarvest", R.string.vetting_field_rely) { it.relianceOnSaHarvest },
            text("trans", "transportCapacity", R.string.vetting_field_trans) { it.transportCapacity },
            chips("meals", "mealsProvided", R.string.vetting_field_meals) { it.mealsProvided },
            chips("days", "daysOfWeek", R.string.vetting_field_days) { it.daysOfWeek },
            FieldSpec("lastFed", "lastDateFed", R.string.vetting_field_lastFed, FieldKind.DATE) { FieldValue.Date(it.lastDateFed) }
        )
    ),
    SectionSpec(
        "facilities", R.string.vetting_section_facilities,
        listOf(
            chips("storage", "foodStorage", R.string.vetting_field_storage) { it.foodStorage },
            file("kitchenPix", "kitchenImages", R.string.vetting_field_kitchenPix) { it.kitchenImages },
            yesNo("clean", "kitchenCleanliness", R.string.vetting_field_clean) { it.kitchenCleanliness },
            yesNo("water", "accessToWater", R.string.vetting_field_water) { it.accessToWater },
            yesNo("toilets", "toilets", R.string.vetting_field_toilets) { it.toilets },
            yesNo("pest", "pestFree", R.string.vetting_field_pest) { it.pestFree },
            chips("infra", "infrastructureChecks", R.string.vetting_field_infra) { it.infrastructureChecks }
        )
    ),
    SectionSpec(
        "access", R.string.vetting_section_access,
        listOf(
            yesNo("access", "easeOfAccess", R.string.vetting_field_access) { it.easeOfAccess },
            yesNo("parking", "parkingSecurity", R.string.vetting_field_parking) { it.parkingSecurity },
            text("police", "policeProximity", R.string.vetting_field_police) { it.policeProximity }
        )
    ),
    SectionSpec(
        "capacity", R.string.vetting_section_capacity,
        listOf(
            text("comments", "additionalComments", R.string.vetting_field_comments, FieldKind.MULTILINE) { it.additionalComments },
            text("proposal", "proposalWriting", R.string.vetting_field_proposal, FieldKind.MULTILINE) { it.proposalWriting },
            text("digital", "digitalCapabilities", R.string.vetting_field_digital, FieldKind.MULTILINE) { it.digitalCapabilities },
            file("facPix", "facilityPhotos", R.string.vetting_field_facPix) { it.facilityPhotos }
        )
    ),
    SectionSpec(
        "documents", R.string.vetting_section_documents,
        listOf(
            yesNo("sla", "hasSla", R.string.vetting_field_sla) { it.hasSla },
            yesNo("consent", "hasConsent", R.string.vetting_field_consent) { it.hasConsent },
            yesNo("policy", "hasPolicy", R.string.vetting_field_policy) { it.hasPolicy },
            file("certs", "certificates", R.string.vetting_field_certs) { it.certificates }
        )
    )
)
