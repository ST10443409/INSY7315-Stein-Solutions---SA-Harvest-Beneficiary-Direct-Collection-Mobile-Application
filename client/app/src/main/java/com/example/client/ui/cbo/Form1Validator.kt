package com.example.client.ui.cbo

import java.util.Locale

enum class Form1Error {
    REQUIRED,
    INVALID_TIME,
    DEPARTURE_BEFORE_ARRIVAL,
    INVALID_QUANTITY,
    NO_PRODUCT_LINES,
    SIGNATURE_REQUIRED,
    PHOTO_REQUIRED
}

/** Identifies which form field an error belongs to. */
enum class Form1Field { ARRIVAL, DEPARTURE, PRODUCTS, DONOR_NAME, SIGNATURES, PHOTOS }

/** Pure validation rules for Form 1 so they can be unit tested without Compose or Android. */
object Form1Validator {

    private val TIME = Regex("^([01]\\d|2[0-3]):[0-5]\\d$")
    private val KG = Regex("^\\d{1,6}([.]\\d{1,3})?$")

    /** Parses a positive kilogram quantity such as "42.5" or "42,5"; null if malformed. */
    fun parseKg(raw: String): Double? {
        val value = raw.trim().replace(',', '.')
        if (!KG.matches(value)) return null
        return value.toDoubleOrNull()?.takeIf { it > 0.0 }
    }

    fun validateKg(raw: String): Form1Error? = when {
        raw.isBlank() -> Form1Error.REQUIRED
        parseKg(raw) == null -> Form1Error.INVALID_QUANTITY
        else -> null
    }

    fun isValidTime(value: String): Boolean = TIME.matches(value)

    fun formatKg(kg: Double): String = String.format(Locale.US, "%.1f", kg)

    fun validate(form: Form1FormState): Map<Form1Field, Form1Error> {
        val errors = linkedMapOf<Form1Field, Form1Error>()

        if (form.arrivalTime.isBlank()) errors[Form1Field.ARRIVAL] = Form1Error.REQUIRED
        else if (!isValidTime(form.arrivalTime)) errors[Form1Field.ARRIVAL] = Form1Error.INVALID_TIME

        val departure = form.departureTime
        if (departure != null) {
            if (!isValidTime(departure)) errors[Form1Field.DEPARTURE] = Form1Error.INVALID_TIME
            else if (Form1Field.ARRIVAL !in errors && departure < form.arrivalTime) {
                // HH:mm strings compare chronologically within a single day.
                errors[Form1Field.DEPARTURE] = Form1Error.DEPARTURE_BEFORE_ARRIVAL
            }
        }

        if (form.productLines.isEmpty()) errors[Form1Field.PRODUCTS] = Form1Error.NO_PRODUCT_LINES
        else if (form.productLines.any { parseKg(it.kg) == null }) {
            errors[Form1Field.PRODUCTS] = Form1Error.INVALID_QUANTITY
        }

        if (form.donorName.isBlank()) errors[Form1Field.DONOR_NAME] = Form1Error.REQUIRED
        if (!form.donorSigned || !form.cboSigned) errors[Form1Field.SIGNATURES] = Form1Error.SIGNATURE_REQUIRED
        if (form.shots.none { it }) errors[Form1Field.PHOTOS] = Form1Error.PHOTO_REQUIRED
        return errors
    }
}
