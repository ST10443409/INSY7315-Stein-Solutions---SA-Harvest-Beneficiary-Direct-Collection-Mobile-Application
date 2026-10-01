package com.example.client.ui.vetting

import com.example.client.data.local.entity.SyncStatus
import com.example.client.data.local.entity.VettingDecision
import com.example.client.network.CboSyncErrorCodes
import com.example.client.sync.SyncPolicy

/** How a decision's sync is shown. FAILED is split so the officer knows whether the app is still trying, and if not, why. */
enum class DecisionSyncDisplay { PENDING, SYNCED, FAILED_WILL_RETRY, FAILED_FINAL, FAILED_REJECTED }

val DecisionSyncDisplay.isFailed: Boolean
    get() = this == DecisionSyncDisplay.FAILED_WILL_RETRY || this == DecisionSyncDisplay.FAILED_FINAL ||
        this == DecisionSyncDisplay.FAILED_REJECTED

fun VettingDecision.syncDisplay(): DecisionSyncDisplay = when (syncStatus) {
    SyncStatus.PENDING -> DecisionSyncDisplay.PENDING
    SyncStatus.SYNCED -> DecisionSyncDisplay.SYNCED
    SyncStatus.FAILED -> when {
        retryCount < SyncPolicy.MAX_RETRIES -> DecisionSyncDisplay.FAILED_WILL_RETRY
        syncErrorCode == CboSyncErrorCodes.VALIDATION_FAILED -> DecisionSyncDisplay.FAILED_REJECTED
        else -> DecisionSyncDisplay.FAILED_FINAL
    }
}

@androidx.annotation.StringRes
fun DecisionSyncDisplay.hintRes(): Int = when (this) {
    DecisionSyncDisplay.PENDING -> com.example.client.R.string.vetting_sync_pending
    DecisionSyncDisplay.SYNCED -> com.example.client.R.string.vetting_sync_synced
    DecisionSyncDisplay.FAILED_WILL_RETRY -> com.example.client.R.string.vetting_sync_retry
    DecisionSyncDisplay.FAILED_FINAL -> com.example.client.R.string.vetting_sync_final
    DecisionSyncDisplay.FAILED_REJECTED -> com.example.client.R.string.vetting_sync_rejected
}
