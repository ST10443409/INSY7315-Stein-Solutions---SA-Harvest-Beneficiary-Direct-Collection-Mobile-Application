package com.example.client.data.repository

import com.example.client.data.local.dao.CboCollectionDao
import com.example.client.data.local.entity.CboCollectionEntity
import com.example.client.data.local.entity.CollectionAttachmentEntity
import com.example.client.data.local.entity.ProductLineEntity
import com.example.client.data.local.entity.SyncStatus
import com.example.client.sync.SyncPolicy
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

// Depends on the DAO only: nothing network-related may be added to this class.
class CboCollectionRepositoryImpl @Inject constructor(
    private val dao: CboCollectionDao
) : CboCollectionRepository {

    override suspend fun save(
        collection: CboCollectionEntity,
        productLines: List<ProductLineEntity>,
        attachments: List<CollectionAttachmentEntity>
    ) {
        // Force PENDING whatever the caller passed; ids are deliberately left untouched.
        dao.insertWithChildren(
            collection.copy(syncStatus = SyncStatus.PENDING),
            productLines.map { it.copy(syncStatus = SyncStatus.PENDING) },
            attachments.map { it.copy(syncStatus = SyncStatus.PENDING) }
        )
    }

    override fun observeAll(): Flow<List<CboCollectionEntity>> = dao.observeAllNewestFirst()

    override fun observeByAuthor(author: String?): Flow<List<CboCollectionEntity>> = dao.observeByAuthorNewestFirst(author)

    override fun observeAttachments(): Flow<List<CollectionAttachmentEntity>> = dao.observeAttachments()

    override fun observeCount(status: SyncStatus): Flow<Int> = dao.observeCountByStatus(status)

    override fun observeWaitingForOthers(author: String?): Flow<Int> =
        dao.observeWaitingForOtherAuthors(author, SyncPolicy.MAX_RETRIES)
}
