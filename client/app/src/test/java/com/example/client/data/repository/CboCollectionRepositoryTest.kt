package com.example.client.data.repository

import com.example.client.testing.InMemoryCboCollectionDao
import com.example.client.data.local.entity.CboCollectionEntity
import com.example.client.data.local.entity.CollectionAttachmentEntity
import com.example.client.data.local.entity.ProductLineEntity
import com.example.client.data.local.entity.SyncStatus
import com.example.client.network.ApiEnvelope
import com.example.client.network.CboSyncRequest
import com.example.client.network.CboSyncResponse
import com.example.client.network.SyncApiService
import com.example.client.network.SyncRequest
import com.example.client.network.SyncResponse
import com.example.client.network.VettingSyncRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import java.io.IOException

class CboCollectionRepositoryTest {

    private class FakeCboCollectionDao : InMemoryCboCollectionDao()

    /** A network that is completely unreachable: any call fails, and every attempt is counted. */
    private class UnreachableSyncApi : SyncApiService {
        var calls = 0
        override suspend fun syncData(request: SyncRequest): Response<SyncResponse> {
            calls++
            throw IOException("Network unreachable")
        }

        override suspend fun syncVettingDecisions(request: VettingSyncRequest): Response<ApiEnvelope<CboSyncResponse>> = error("unused")

        override suspend fun syncCboCollections(request: CboSyncRequest): Response<ApiEnvelope<CboSyncResponse>> {
            calls++
            throw IOException("Network unreachable")
        }
    }

    private lateinit var dao: FakeCboCollectionDao
    private lateinit var network: UnreachableSyncApi
    private lateinit var repository: CboCollectionRepository

    @Before
    fun setUp() {
        dao = FakeCboCollectionDao()
        network = UnreachableSyncApi()
        // The repository takes only the DAO; the unreachable network exists to prove it is never used.
        repository = CboCollectionRepositoryImpl(dao)
    }

    private fun collection(id: String = "c1", createdAt: Long = 1L, status: SyncStatus = SyncStatus.PENDING) =
        CboCollectionEntity(
            id = id,
            cboId = "cbo",
            arrivalTime = "09:00",
            departureTime = null,
            donorName = "Donor",
            donorSigned = true,
            cboSigned = true,
            deliveryNote = "",
            noteAttached = false,
            collectNotes = "",
            shots = listOf(true),
            latitude = null,
            longitude = null,
            syncStatus = status,
            createdAt = createdAt
        )

    private fun line(collectionId: String = "c1", id: String = "l1") =
        ProductLineEntity(id = id, collectionId = collectionId, category = "Fruit", kg = "10", notes = null)

    @Test
    fun save_succeedsWithNetworkUnreachable_andPersistsAsPending() = runTest {
        repository.save(collection(), listOf(line()))

        val stored = repository.observeAll().first()
        assertEquals(1, stored.size)
        assertEquals(SyncStatus.PENDING, stored.single().syncStatus)
        assertEquals(1, dao.productLines.size)
        assertEquals(SyncStatus.PENDING, dao.productLines.single().syncStatus)
        assertEquals(0, network.calls)
    }

    @Test
    fun save_forcesPending_evenIfCallerPassesAnotherStatus() = runTest {
        repository.save(collection(status = SyncStatus.SYNCED), listOf(line().copy(syncStatus = SyncStatus.FAILED)))

        assertEquals(SyncStatus.PENDING, repository.observeAll().first().single().syncStatus)
        assertEquals(SyncStatus.PENDING, dao.productLines.single().syncStatus)
    }

    @Test
    fun save_keepsIds_soRetryOverwritesInsteadOfDuplicating() = runTest {
        repository.save(collection(id = "same"), listOf(line(collectionId = "same", id = "l1")))
        repository.save(collection(id = "same"), listOf(line(collectionId = "same", id = "l1")))

        val stored = repository.observeAll().first()
        assertEquals(listOf("same"), stored.map { it.id })
        assertEquals(1, dao.productLines.size)
    }

    @Test
    fun observeAll_isNewestFirst() = runTest {
        repository.save(collection(id = "old", createdAt = 1L), emptyList())
        repository.save(collection(id = "new", createdAt = 3L), emptyList())
        repository.save(collection(id = "mid", createdAt = 2L), emptyList())

        assertEquals(listOf("new", "mid", "old"), repository.observeAll().first().map { it.id })
    }

    @Test
    fun observeCount_reflectsStatusChangesMadeUnderneath() = runTest {
        repository.save(collection(id = "a"), emptyList())
        repository.save(collection(id = "b"), emptyList())
        assertEquals(2, repository.observeCount(SyncStatus.PENDING).first())

        // What the sync worker does once a record has been uploaded.
        dao.update(dao.getAll().first().first { it.id == "a" }.copy(syncStatus = SyncStatus.SYNCED))

        assertEquals(1, repository.observeCount(SyncStatus.PENDING).first())
        assertEquals(1, repository.observeCount(SyncStatus.SYNCED).first())
        assertTrue(repository.observeAll().first().any { it.syncStatus == SyncStatus.SYNCED })
    }
}
