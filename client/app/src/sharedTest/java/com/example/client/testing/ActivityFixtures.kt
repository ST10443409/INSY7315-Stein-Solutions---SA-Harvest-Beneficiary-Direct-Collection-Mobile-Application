package com.example.client.testing

import com.example.client.auth.UserRole
import com.example.client.data.repository.ActivityFilter
import com.example.client.data.repository.ActivityItem
import com.example.client.data.repository.ActivityPage
import com.example.client.data.repository.AdminResult
import com.example.client.data.repository.AdminUserActivityRepository
import com.example.client.data.repository.SyncForm
import kotlinx.coroutines.CompletableDeferred

// Shared by src/test and src/androidTest: the user activity (#51) fixtures.

fun sampleActivity(
    id: String = "c1",
    form: SyncForm = SyncForm.CBO_COLLECTION,
    user: String? = "cbo_test_user",
    role: UserRole? = UserRole.CBO_COLLECTION,
    label: String = "Donor $id · delivery note DN-1"
) = ActivityItem(id, form, user, role, atMillis = 1_700_000_000_000L, label = label)

fun sampleActivityPage(
    items: List<ActivityItem> = listOf(sampleActivity()),
    page: Int = 1,
    totalCount: Int = items.size,
    hasMore: Boolean = false,
    from: String? = "2026-09-26",
    to: String? = "2026-10-02"
) = ActivityPage(items, page, totalCount, hasMore, from, to)

class FakeAdminUserActivityRepository : AdminUserActivityRepository {
    /** What each call returns, by default; a test can also set [handler] to decide per call. */
    var result: AdminResult<ActivityPage> = AdminResult.Success(sampleActivityPage())
    var handler: (suspend (ActivityFilter, Int) -> AdminResult<ActivityPage>)? = null

    /** Every call made: the filter and the page asked for. */
    val calls = mutableListOf<Pair<ActivityFilter, Int>>()

    /** When set, a call waits for it, so a test can look at the screen while a load is running. */
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun page(filter: ActivityFilter, page: Int): AdminResult<ActivityPage> {
        calls += filter to page
        gate?.await()
        return handler?.invoke(filter, page) ?: result
    }
}
