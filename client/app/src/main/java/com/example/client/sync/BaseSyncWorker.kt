package com.example.client.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.client.auth.RoleProvider
import com.example.client.auth.UserRole
import java.io.IOException

enum class SyncRunResult { DONE, RETRY_LATER }

/**
 * Shared retry/backoff handling for the sync workers (CBO collections now, Vetting decisions
 * next). A subclass only says how to sync; this class decides how a run ends:
 * - nobody who may send these records is signed in: the run ends at once, without touching the network;
 * - whoever is signed in sends only the records they captured themselves (#70);
 * - [SyncRunResult.RETRY_LATER], or a network error, retries with WorkManager's backoff, up to [MAX_ATTEMPTS];
 * - after that the run ends as a failure, and the periodic schedule picks the work up again later.
 */
abstract class BaseSyncWorker(
    context: Context,
    params: WorkerParameters,
    private val roleProvider: RoleProvider
) : CoroutineWorker(context, params) {

    /** The roles the backend accepts these records from. */
    protected abstract val syncRoles: Set<UserRole>

    /** Sends what [username], the signed-in user, captured. Never anyone else's records: the server attributes them to the token's owner (#70). */
    protected abstract suspend fun performSync(username: String): SyncRunResult

    final override suspend fun doWork(): Result {
        // Signed out (e.g. the session expired in the field), or signed in as a role these records are not for: every
        // request would be refused, and on 2G each refusal costs data on every retry (#55). The records stay as they
        // are; signing in queues a sync straight away (MainActivity).
        if (roleProvider.currentRole.value !in syncRoles) return Result.success()
        // A sign-in always has a username. Without one there is no author to send for, and sending anything would put
        // it under an unknown name (#70).
        val username = roleProvider.currentUsername() ?: return Result.success()

        val outcome = try {
            performSync(username)
        } catch (e: IOException) {
            SyncRunResult.RETRY_LATER
        }
        return when (outcome) {
            SyncRunResult.DONE -> Result.success()
            SyncRunResult.RETRY_LATER ->
                if (runAttemptCount + 1 >= MAX_ATTEMPTS) Result.failure() else Result.retry()
        }
    }

    companion object {
        const val MAX_ATTEMPTS = 5
    }
}
