package com.example.client.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.WorkerParameters
import com.example.client.auth.RoleProvider
import com.example.client.auth.UserRole
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Sends locally stored PENDING (and retryable FAILED) CBO collections to the backend, and then the signatures and photos that
 * belong to collections the server now has. Records go first and on their own: a slow photo can never hold a record back, and a
 * file is only ever offered once its record is safe on the server.
 */
@HiltWorker
class CboSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val processor: CboSyncProcessor,
    private val attachments: AttachmentUploadProcessor,
    roleProvider: RoleProvider
) : BaseSyncWorker(context, params, roleProvider) {

    /** Who POST /api/cbo-collection/sync accepts (and so who may upload files for those collections). */
    override val syncRoles = setOf(UserRole.CBO_COLLECTION, UserRole.ADMIN)

    override suspend fun performSync(username: String): SyncRunResult {
        val records = processor.syncPending(username)
        val files = attachments.uploadPending(username)
        return if (records == SyncRunResult.RETRY_LATER || files == SyncRunResult.RETRY_LATER) SyncRunResult.RETRY_LATER else SyncRunResult.DONE
    }
}
