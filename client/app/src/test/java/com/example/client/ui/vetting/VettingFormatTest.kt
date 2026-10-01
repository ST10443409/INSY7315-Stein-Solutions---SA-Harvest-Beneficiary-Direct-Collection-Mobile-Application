package com.example.client.ui.vetting

import com.example.client.data.parseIsoInstantMillis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VettingFormatTest {

    private val minute = 60_000L
    private val hour = 60 * minute

    @Test
    fun age_usesTheCoarsestNaturalUnit() {
        val now = 10_000_000_000L
        assertEquals(Age.JustNow, ageOf(now, now))
        assertEquals(Age.JustNow, ageOf(now - 59_000, now))
        assertEquals(Age.Minutes(1), ageOf(now - minute, now))
        assertEquals(Age.Minutes(59), ageOf(now - 59 * minute, now))
        assertEquals(Age.Hours(1), ageOf(now - hour, now))
        assertEquals(Age.Hours(23), ageOf(now - 23 * hour - 59 * minute, now))
        assertEquals(Age.Days(1), ageOf(now - 24 * hour, now))
        assertEquals(Age.Days(9), ageOf(now - 9 * 24 * hour, now))
    }

    @Test
    fun aTimestampInTheFuture_countsAsJustNow() {
        assertEquals(Age.JustNow, ageOf(thenMillis = 5_000_000, nowMillis = 1_000_000))
    }

    @Test
    fun theDateIsFormattedAsTheFormDoes_inSouthAfricanTime() {
        // 2026-10-01 22:30 UTC is already 2026-10-02 00:30 in South Africa (UTC+2).
        val utc = parseIsoInstantMillis("2026-10-01T22:30:00Z")!!
        assertEquals("2026/10/02", formatFieldDate(utc))
        assertEquals("2026/10/01", formatFieldDate(utc - 3 * hour))
    }

    @Test
    fun isoTimestamps_areParsedWithTheirOffset() {
        val expected = 1_790_000_000_000L // 2026-09-21T14:13:20Z
        assertEquals(expected, parseIsoInstantMillis("2026-09-21T14:13:20Z"))
        assertEquals(expected, parseIsoInstantMillis("2026-09-21T14:13:20+00:00"))
        assertEquals(expected, parseIsoInstantMillis("2026-09-21T16:13:20+02:00"))
        assertEquals(expected, parseIsoInstantMillis("2026-09-21T09:43:20-04:30"))
    }

    @Test
    fun theBackendsSevenDigitFractions_areKeptToTheMillisecond() {
        assertEquals(
            parseIsoInstantMillis("2026-10-01T19:19:23Z")!! + 871,
            parseIsoInstantMillis("2026-10-01T19:19:23.8718392+00:00")
        )
        assertEquals(parseIsoInstantMillis("2026-10-01T19:19:23Z")!! + 500, parseIsoInstantMillis("2026-10-01T19:19:23.5Z"))
    }

    @Test
    fun anythingElse_isNull() {
        assertNull(parseIsoInstantMillis(null))
        assertNull(parseIsoInstantMillis(""))
        assertNull(parseIsoInstantMillis("yesterday"))
        assertNull(parseIsoInstantMillis("2026-10-01"))
        assertNull(parseIsoInstantMillis("2026-10-01T19:19:23")) // no offset: ambiguous
    }
}
