package com.example.client.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.WorkerParameters
import com.example.client.auth.RoleProvider
import com.example.client.auth.UserRole
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/** Sends locally stored PENDING (and retryable FAILED) CBO collections to the backend. */
@HiltWorker
class CboSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val processor: CboSyncProcessor,
    roleProvider: RoleProvider
) : BaseSyncWorker(context, params, roleProvider) {

    /** Who POST /api/cbo-collection/sync accepts. */
    override val syncRoles = setOf(UserRole.CBO_COLLECTION, UserRole.ADMIN)

    override suspend fun performSync(): SyncRunResult = processor.syncPending()
}
