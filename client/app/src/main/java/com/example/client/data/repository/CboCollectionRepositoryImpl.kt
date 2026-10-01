package com.example.client.data.repository

import com.example.client.data.local.dao.CboCollectionDao
import com.example.client.data.local.entity.CboCollectionEntity
import com.example.client.data.local.entity.ProductLineEntity
import com.example.client.data.local.entity.SyncStatus
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

// Depends on the DAO only: nothing network-related may be added to this class.
class CboCollectionRepositoryImpl @Inject constructor(
    private val dao: CboCollectionDao
) : CboCollectionRepository {

    override suspend fun save(collection: CboCollectionEntity, productLines: List<ProductLineEntity>) {
        // Force PENDING whatever the caller passed; ids are deliberately left untouched.
        dao.insertWithProductLines(
            collection.copy(syncStatus = SyncStatus.PENDING),
            productLines.map { it.copy(syncStatus = SyncStatus.PENDING) }
        )
    }

    override fun observeAll(): Flow<List<CboCollectionEntity>> = dao.observeAllNewestFirst()

    override fun observeCount(status: SyncStatus): Flow<Int> = dao.observeCountByStatus(status)
}
