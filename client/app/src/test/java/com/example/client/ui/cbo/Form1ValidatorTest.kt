package com.example.client.ui.cbo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Form1ValidatorTest {

    private fun validForm() = Form1FormState(
        arrivalTime = "09:42",
        departureTime = "10:26",
        productLines = listOf(ProductLineInput(category = "Fruit", kg = "42.5", notes = "")),
        donorName = "Jane Doe",
        donorSigned = true,
        cboSigned = true,
        shots = listOf(true, false, false, false)
    )

    @Test
    fun validForm_hasNoErrors() {
        assertTrue(Form1Validator.validate(validForm()).isEmpty())
    }

    @Test
    fun emptyForm_flagsEveryRequiredField() {
        val errors = Form1Validator.validate(Form1FormState(arrivalTime = "09:42"))
        assertEquals(Form1Error.NO_PRODUCT_LINES, errors[Form1Field.PRODUCTS])
        assertEquals(Form1Error.REQUIRED, errors[Form1Field.DONOR_NAME])
        assertEquals(Form1Error.SIGNATURE_REQUIRED, errors[Form1Field.SIGNATURES])
        assertEquals(Form1Error.PHOTO_REQUIRED, errors[Form1Field.PHOTOS])
    }

    @Test
    fun blankDonorName_isRejected() {
        val errors = Form1Validator.validate(validForm().copy(donorName = "   "))
        assertEquals(Form1Error.REQUIRED, errors[Form1Field.DONOR_NAME])
    }

    @Test
    fun bothSignaturesRequired() {
        val errors = Form1Validator.validate(validForm().copy(cboSigned = false))
        assertEquals(Form1Error.SIGNATURE_REQUIRED, errors[Form1Field.SIGNATURES])
    }

    @Test
    fun malformedTimes_areRejected() {
        assertEquals(
            Form1Error.INVALID_TIME,
            Form1Validator.validate(validForm().copy(arrivalTime = "9:42"))[Form1Field.ARRIVAL]
        )
        assertEquals(
            Form1Error.INVALID_TIME,
            Form1Validator.validate(validForm().copy(departureTime = "25:00"))[Form1Field.DEPARTURE]
        )
    }

    @Test
    fun departureBeforeArrival_isRejected() {
        val errors = Form1Validator.validate(validForm().copy(departureTime = "08:00"))
        assertEquals(Form1Error.DEPARTURE_BEFORE_ARRIVAL, errors[Form1Field.DEPARTURE])
    }

    @Test
    fun departureIsOptional() {
        assertTrue(Form1Validator.validate(validForm().copy(departureTime = null)).isEmpty())
    }

    @Test
    fun kgParsing() {
        assertEquals(42.5, Form1Validator.parseKg("42.5")!!, 0.0)
        assertEquals(42.5, Form1Validator.parseKg(" 42,5 ")!!, 0.0)
        assertNull(Form1Validator.parseKg("abc"))
        assertNull(Form1Validator.parseKg("0"))
        assertNull(Form1Validator.parseKg("-3"))
        assertNull(Form1Validator.parseKg("1.2345"))
        assertNull(Form1Validator.parseKg("1e3"))
    }

    @Test
    fun kgValidation() {
        assertEquals(Form1Error.REQUIRED, Form1Validator.validateKg(""))
        assertEquals(Form1Error.INVALID_QUANTITY, Form1Validator.validateKg("12kg"))
        assertNull(Form1Validator.validateKg("12"))
    }

    @Test
    fun malformedProductQuantity_isRejected() {
        val form = validForm().copy(productLines = listOf(ProductLineInput(category = "Fruit", kg = "x", notes = "")))
        assertEquals(Form1Error.INVALID_QUANTITY, Form1Validator.validate(form)[Form1Field.PRODUCTS])
    }
}
