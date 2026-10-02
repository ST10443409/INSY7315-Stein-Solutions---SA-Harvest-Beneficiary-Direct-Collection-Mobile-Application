package com.example.client.testing

import com.example.client.data.local.dao.CboCollectionDao
import com.example.client.data.local.entity.CboCollectionEntity
import com.example.client.data.local.entity.CollectionAttachmentEntity
import com.example.client.data.local.entity.ProductLineEntity
import com.example.client.data.local.entity.SyncStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

// Shared by src/test and src/androidTest (see the sharedTest source set in app/build.gradle.kts).

/**
 * The collections DAO in memory, with the same rules as its SQL: which records are due, who may send them (an author's
 * own, plus records with no recorded author), and which pictures may upload. The JVM tests use it where the real Room
 * queries are covered separately by the instrumented DAO tests, so a rule changed in one place and not the other shows up.
 */
open class InMemoryCboCollectionDao : CboCollectionDao() {
    val rows = MutableStateFlow<List<CboCollectionEntity>>(emptyList())
    val productLines = mutableListOf<ProductLineEntity>()
    val attachments = MutableStateFlow<List<CollectionAttachmentEntity>>(emptyList())

    fun get(id: String) = rows.value.first { it.id == id }
    fun attachment(id: String) = attachments.value.first { it.id == id }

    private fun sameUser(author: String?, other: String?) = author != null && other != null && author.equals(other, ignoreCase = true)

    override suspend fun insert(collection: CboCollectionEntity) {
        rows.value = rows.value.filterNot { it.id == collection.id } + collection
    }

    override suspend fun insertProductLines(productLines: List<ProductLineEntity>) {
        productLines.forEach { line ->
            this.productLines.removeAll { it.id == line.id }
            this.productLines += line
        }
    }

    override suspend fun insertAttachments(attachments: List<CollectionAttachmentEntity>) {
        this.attachments.value = this.attachments.value.filterNot { old -> attachments.any { it.id == old.id } } + attachments
    }

    override suspend fun getAttachmentsForCollections(collectionIds: List<String>) =
        attachments.value.filter { it.collectionId in collectionIds }

    override fun observeAttachments(): Flow<List<CollectionAttachmentEntity>> = attachments

    override suspend fun update(collection: CboCollectionEntity) = insert(collection)

    override fun getBySyncStatus(status: SyncStatus): Flow<List<CboCollectionEntity>> =
        rows.map { l -> l.filter { it.syncStatus == status } }

    override fun getAll(): Flow<List<CboCollectionEntity>> = rows

    override fun observeAllNewestFirst(): Flow<List<CboCollectionEntity>> = rows.map { l -> l.sortedByDescending { it.createdAt } }

    override fun observeByAuthorNewestFirst(author: String?): Flow<List<CboCollectionEntity>> =
        observeAllNewestFirst().map { l -> l.filter { it.authorUsername == null || sameUser(author, it.authorUsername) } }

    override suspend fun getSyncable(maxRetries: Int, author: String): List<CboCollectionEntity> = rows.value
        .filter { it.syncStatus == SyncStatus.PENDING || (it.syncStatus == SyncStatus.FAILED && it.retryCount < maxRetries) }
        .filter { it.authorUsername == null || sameUser(author, it.authorUsername) }
        .sortedBy { it.createdAt }

    override suspend fun markSynced(ids: List<String>, now: Long) {
        rows.value = rows.value.map { if (it.id in ids) it.copy(syncStatus = SyncStatus.SYNCED, syncErrorCode = null) else it }
    }

    override suspend fun markFailed(ids: List<String>, errorCode: String?, now: Long) {
        rows.value = rows.value.map {
            if (it.id in ids) it.copy(syncStatus = SyncStatus.FAILED, retryCount = it.retryCount + 1, syncErrorCode = errorCode) else it
        }
    }

    override suspend fun markRejected(ids: List<String>, errorCode: String?, maxRetries: Int, now: Long) {
        rows.value = rows.value.map {
            if (it.id in ids) it.copy(syncStatus = SyncStatus.FAILED, retryCount = maxRetries, syncErrorCode = errorCode) else it
        }
    }

    override fun observeCountByStatus(status: SyncStatus): Flow<Int> = rows.map { l -> l.count { it.syncStatus == status } }

    override fun observeWaitingForOtherAuthors(author: String?, maxRetries: Int): Flow<Int> = rows.map { l ->
        l.count {
            (it.syncStatus == SyncStatus.PENDING || (it.syncStatus == SyncStatus.FAILED && it.retryCount < maxRetries)) &&
                it.authorUsername != null && !sameUser(author, it.authorUsername)
        }
    }

    override suspend fun getUploadableAttachments(maxRetries: Int, author: String): List<CollectionAttachmentEntity> {
        val serverHas = rows.value
            .filter { it.syncStatus == SyncStatus.SYNCED || (it.syncStatus == SyncStatus.FAILED && it.syncErrorCode == "DUPLICATE_DETECTED") }
            .filter { it.authorUsername == null || sameUser(author, it.authorUsername) }
            .map { it.id }
            .toSet()
        return attachments.value
            .filter { it.collectionId in serverHas }
            .filter { it.syncStatus == SyncStatus.PENDING || (it.syncStatus == SyncStatus.FAILED && it.retryCount < maxRetries) }
            .sortedBy { it.createdAt }
    }

    override suspend fun markAttachmentsUploaded(ids: List<String>, now: Long) {
        attachments.value = attachments.value.map {
            if (it.id in ids) it.copy(syncStatus = SyncStatus.SYNCED, syncErrorCode = null, updatedAt = now) else it
        }
    }

    override suspend fun markAttachmentsRejected(ids: List<String>, errorCode: String?, maxRetries: Int, now: Long) {
        attachments.value = attachments.value.map {
            if (it.id in ids) it.copy(syncStatus = SyncStatus.FAILED, retryCount = maxRetries, syncErrorCode = errorCode, updatedAt = now) else it
        }
    }
}
