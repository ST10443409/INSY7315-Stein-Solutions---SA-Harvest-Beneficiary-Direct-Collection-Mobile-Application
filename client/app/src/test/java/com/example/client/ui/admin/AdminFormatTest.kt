package com.example.client.ui.admin

import com.example.client.data.repository.SyncState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

class AdminFormatTest {

    @Test
    fun aTime_isShownInTheGivenTimeZone() {
        // 2023-11-14T22:13:20Z
        assertEquals("14 Nov 2023, 22:13", formatWhen(1_700_000_000_000L, TimeZone.getTimeZone("UTC"), Locale.ENGLISH))
        assertEquals("15 Nov 2023, 00:13", formatWhen(1_700_000_000_000L, TimeZone.getTimeZone("Africa/Johannesburg"), Locale.ENGLISH))
    }

    @Test
    fun noTime_isNull() {
        assertNull(formatWhen(null))
    }

    @Test
    fun everyState_hasItsOwnLabel() {
        val labels = SyncState.values().map { it.labelRes() }

        assertEquals(labels.size, labels.toSet().size)
        assertTrue(labels.all { it != 0 })
    }

    @Test
    fun theStateThatNeedsAnAdmin_isNotColouredLikeOneThatIsFine() {
        assertNotEquals(SyncState.NEEDS_ATTENTION.palette(), SyncState.FORWARDED.palette())
        assertNotEquals(SyncState.NEEDS_ATTENTION.palette(), SyncState.DUPLICATE_HELD.palette())
    }

    @Test
    fun outcomes_followTheStateTheServerReports() {
        fun outcome(state: SyncState) = outcomeOf(com.example.client.testing.sampleResolution(record = com.example.client.testing.sampleDetail(state = state)))

        assertEquals(ActionOutcome.SENT, outcome(SyncState.FORWARDED))
        assertEquals(ActionOutcome.STILL_RETRYING, outcome(SyncState.RETRYING))
        assertEquals(ActionOutcome.REJECTED_AGAIN, outcome(SyncState.NEEDS_ATTENTION))
        assertEquals(ActionOutcome.SUPERSEDED, outcome(SyncState.SUPERSEDED))
        assertEquals(ActionOutcome.DISMISSED, outcome(SyncState.DISMISSED))
        assertEquals(ActionOutcome.OTHER, outcome(SyncState.WAITING))
    }
}
