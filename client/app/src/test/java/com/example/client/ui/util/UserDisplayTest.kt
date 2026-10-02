package com.example.client.ui.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class UserDisplayTest {

    @Test
    fun theName_isTheUsernameWithACapital() {
        assertEquals("Sipho", displayName("sipho"))
        assertEquals("Cbo_user", displayName("  cbo_user "))
    }

    @Test
    fun withNoUsername_thereIsNoName() {
        assertNull(displayName(null))
        assertNull(displayName("   "))
    }

    @Test
    fun theInitials_comeFromTheFirstTwoWords_orTheFirstTwoLetters() {
        assertEquals("SN", initialsOf("sipho.ndlovu"))
        assertEquals("TM", initialsOf("thandi mokoena"))
        assertEquals("VE", initialsOf("vetting"))
        assertEquals("A", initialsOf("a"))
    }

    @Test
    fun withNoUsername_theInitialsAreAPlaceholder() {
        assertEquals("?", initialsOf(null))
        assertEquals("?", initialsOf(""))
        assertEquals("?", initialsOf("..."))
    }

    @Test
    fun twoMomentsOnTheSameDay_areTheSameDay() {
        val morning = Calendar.getInstance().apply { set(2026, Calendar.AUGUST, 14, 6, 0, 0) }.timeInMillis
        val evening = Calendar.getInstance().apply { set(2026, Calendar.AUGUST, 14, 22, 30, 0) }.timeInMillis
        assertTrue(isSameDay(morning, evening))
    }

    @Test
    fun momentsOnDifferentDays_areNot() {
        val thursday = Calendar.getInstance().apply { set(2026, Calendar.AUGUST, 13, 23, 59, 0) }.timeInMillis
        val friday = Calendar.getInstance().apply { set(2026, Calendar.AUGUST, 14, 0, 1, 0) }.timeInMillis
        assertFalse(isSameDay(thursday, friday))
        val nextYear = Calendar.getInstance().apply { set(2027, Calendar.AUGUST, 14, 12, 0, 0) }.timeInMillis
        assertFalse(isSameDay(friday, nextYear))
    }
}
