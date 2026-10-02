package com.example.client.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.WorkerParameters
import com.example.client.auth.RoleProvider
import com.example.client.auth.UserRole
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
    private val processor: VettingSyncProcessor,
    roleProvider: RoleProvider
) : BaseSyncWorker(context, params, roleProvider) {

    /** Who POST /api/vetting/sync accepts. */
    override val syncRoles = setOf(UserRole.VETTING, UserRole.ADMIN)

    override suspend fun performSync(username: String): SyncRunResult = processor.syncPending(username)
}
