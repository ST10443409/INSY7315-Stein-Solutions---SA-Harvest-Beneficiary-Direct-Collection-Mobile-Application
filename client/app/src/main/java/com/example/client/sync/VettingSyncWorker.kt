package com.example.client.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Sends locally stored PENDING (and retryable FAILED) vetting decisions to the backend. Like [CboSyncWorker], it says
 * only how to sync; [BaseSyncWorker] owns how a run ends (retry with backoff, then give up until the periodic run).
 */
@HiltWorker
class VettingSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val processor: VettingSyncProcessor
) : BaseSyncWorker(context, params) {

    override suspend fun performSync(): SyncRunResult = processor.syncPending()
}
