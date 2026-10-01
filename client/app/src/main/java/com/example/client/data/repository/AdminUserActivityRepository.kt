package com.example.client.data.repository

import com.example.client.auth.UserRole
import com.example.client.data.parseIsoInstantMillis
import com.example.client.network.AdminApiService
import com.example.client.network.UserActivityItemDto
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What to look at. Every field is optional: no [user] and no [role] means everyone, and with no [from] and no [to] the server
 * applies its own recent window (the answer says which). [from] and [to] are `yyyy-MM-dd` South African days, [to] inclusive.
 */
data class ActivityFilter(
    val user: String? = null,
    val role: UserRole? = null,
    val from: String? = null,
    val to: String? = null
)

/** One thing a user did. [form] and [id] are the record reference. [role] is the account's role today, null if unknown. */
data class ActivityItem(
    val id: String,
    val form: SyncForm,
    val user: String?,
    val role: UserRole?,
    val atMillis: Long?,
    val label: String
)

/** One page of activity, newest first, plus the dates the server actually applied. */
data class ActivityPage(
    val items: List<ActivityItem>,
    val page: Int,
    val totalCount: Int,
    val hasMore: Boolean,
    val from: String?,
    val to: String?
)

/** Who submitted or vetted what, and when (backend #51). Read-only, and every call needs the server. */
interface AdminUserActivityRepository {
    /** Page [page] (from 1) of the activity matching [filter]. */
    suspend fun page(filter: ActivityFilter, page: Int): AdminResult<ActivityPage>
}

@Singleton
class AdminUserActivityRepositoryImpl @Inject constructor(
    private val api: AdminApiService
) : AdminUserActivityRepository {

    override suspend fun page(filter: ActivityFilter, page: Int): AdminResult<ActivityPage> =
        adminCall {
            api.getUserActivity(
                user = filter.user?.trim()?.takeIf { it.isNotEmpty() },
                role = filter.role?.name,
                from = filter.from?.trim()?.takeIf { it.isNotEmpty() },
                to = filter.to?.trim()?.takeIf { it.isNotEmpty() },
                page = page,
                pageSize = PAGE_SIZE
            )
        }.map { dto ->
            ActivityPage(dto.items.map { it.toModel() }, dto.page, dto.totalCount, dto.hasMore, dto.from, dto.to)
        }

    private fun UserActivityItemDto.toModel() = ActivityItem(
        id = id,
        form = SyncForm.values().firstOrNull { it.name == form } ?: SyncForm.CBO_COLLECTION,
        user = user,
        role = UserRole.values().firstOrNull { it.name == role },
        atMillis = parseIsoInstantMillis(at),
        label = label
    )

    companion object {
        const val PAGE_SIZE = 50
    }
}
