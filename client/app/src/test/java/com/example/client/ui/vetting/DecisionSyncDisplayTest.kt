package com.example.client.ui.vetting

import com.example.client.data.local.entity.SyncStatus
import com.example.client.network.CboSyncErrorCodes
import com.example.client.sync.SyncPolicy
import com.example.client.testing.sampleDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DecisionSyncDisplayTest {

    private fun decision(status: SyncStatus, retryCount: Int = 0, code: String? = null) =
        sampleDecision().copy(syncStatus = status, retryCount = retryCount, syncErrorCode = code)

    @Test
    fun eachStatus_mapsToItsOwnDisplay() {
        assertEquals(DecisionSyncDisplay.PENDING, decision(SyncStatus.PENDING).syncDisplay())
        assertEquals(DecisionSyncDisplay.SYNCED, decision(SyncStatus.SYNCED).syncDisplay())
        assertEquals(DecisionSyncDisplay.FAILED_WILL_RETRY, decision(SyncStatus.FAILED, 1).syncDisplay())
    }

    @Test
    fun aFailureOutOfRetries_isFinal_unlessTheServerRefusedIt() {
        assertEquals(DecisionSyncDisplay.FAILED_FINAL, decision(SyncStatus.FAILED, SyncPolicy.MAX_RETRIES, "SERVER_ERROR").syncDisplay())
        assertEquals(DecisionSyncDisplay.FAILED_FINAL, decision(SyncStatus.FAILED, SyncPolicy.MAX_RETRIES, null).syncDisplay())
        assertEquals(
            DecisionSyncDisplay.FAILED_REJECTED,
            decision(SyncStatus.FAILED, SyncPolicy.MAX_RETRIES, CboSyncErrorCodes.VALIDATION_FAILED).syncDisplay()
        )
    }

    @Test
    fun aDecisionStillHavingRetries_isNeverShownAsRejected() {
        assertEquals(DecisionSyncDisplay.FAILED_WILL_RETRY, decision(SyncStatus.FAILED, 1, CboSyncErrorCodes.VALIDATION_FAILED).syncDisplay())
    }

    @Test
    fun onlyTheFailureKinds_countAsFailed() {
        assertTrue(listOf(DecisionSyncDisplay.FAILED_WILL_RETRY, DecisionSyncDisplay.FAILED_FINAL, DecisionSyncDisplay.FAILED_REJECTED).all { it.isFailed })
        assertFalse(DecisionSyncDisplay.PENDING.isFailed)
        assertFalse(DecisionSyncDisplay.SYNCED.isFailed)
    }

    @Test
    fun everyDisplay_hasItsOwnHint() {
        val hints = DecisionSyncDisplay.values().map { it.hintRes() }
        assertEquals(hints.size, hints.toSet().size)
    }
}
