package com.example.client.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A read-only/cached entity that mirrors only the subset of Foodspace fields
 * a vetting officer actually needs to see for Form 2 (Vetting).
 * 
 * Nothing in the app should write back to this table except the fetch-and-replace sync path.
 */
@Entity(tableName = "foodspace_beneficiary_records")
data class FoodspaceBeneficiaryRecord(
    // Primary key from Foodspace's own record ID
    @PrimaryKey
    val id: String,

    // Organisation & contact
    val legalName: String,
    val contactName: String,
    val contactEmail: String,
    val contactPhone: String,
    val website: String?,

    // Location
    val address: String?,
    val address2: String?,
    val province: String,
    val what3words: String,

    // Programmes & services
    val coreBusiness: String,
    val targetPopulation: List<String>,
    val programmes: String,
    val distributionChannel: String,

    // Staffing
    val fullTimeFemales: Int,
    val fullTimeMales: Int,
    val volunteers: Int,

    // Registration
    val registeredNpo: Boolean,
    val npoCertificate: String?,
    val registeredDsd: Boolean,
    val pboCertificate: String?,

    // Beneficiary demographics
    val race: List<String>,
    val gender: List<String>,
    val ageGroups: List<String>,

    // Feeding operation
    val feedingFrequency: String,
    val totalServed: Int,
    val femalesServed: Int,
    val malesServed: Int,
    val africanServed: Int,
    val colouredServed: Int,
    val indianServed: Int,
    val whiteServed: Int,
    val relianceOnSaHarvest: String,
    val transportCapacity: String,
    val mealsProvided: List<String>,
    val daysOfWeek: List<String>,
    val lastDateFed: Long?, // Timestamp

    // Facilities & hygiene
    val foodStorage: List<String>,
    val kitchenImages: String?,
    val kitchenCleanliness: Boolean,
    val accessToWater: Boolean,
    val toilets: Boolean,
    val pestFree: Boolean,
    val infrastructureChecks: List<String>,

    // Access & security
    val easeOfAccess: Boolean,
    val parkingSecurity: Boolean,
    val policeProximity: String?,

    // Capacity notes
    val additionalComments: String?,
    val proposalWriting: String?,
    val digitalCapabilities: String?,
    val facilityPhotos: String?,

    // Documents
    val hasSla: Boolean,
    val hasConsent: Boolean,
    val hasPolicy: Boolean,
    val certificates: String?
)
