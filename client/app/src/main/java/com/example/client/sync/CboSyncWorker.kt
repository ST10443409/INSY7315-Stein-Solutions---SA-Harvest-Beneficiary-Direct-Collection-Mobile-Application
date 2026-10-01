package com.example.client.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/** Sends locally stored PENDING (and retryable FAILED) CBO collections to the backend. */
@HiltWorker
class CboSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val processor: CboSyncProcessor
) : BaseSyncWorker(context, params) {

    override suspend fun performSync(): SyncRunResult = processor.syncPending()
}
