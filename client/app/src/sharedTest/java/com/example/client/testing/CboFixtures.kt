package com.example.client.testing

import com.example.client.data.attachments.AttachmentStorage
import com.example.client.data.attachments.CaptureTarget
import com.example.client.data.attachments.StoredFile
import com.example.client.data.local.entity.CboCollectionEntity
import com.example.client.data.local.entity.CollectionAttachmentEntity
import com.example.client.data.local.entity.ProductLineEntity
import com.example.client.data.local.entity.SyncStatus
import com.example.client.data.repository.CboCollectionRepository
import com.example.client.sync.CboSyncTrigger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

// Shared by src/test and src/androidTest (see the sharedTest source set in app/build.gradle.kts).

/** Stores nothing: it records which "files" exist, so a test can see what was saved and what was cleaned up. */
class FakeAttachmentStorage : AttachmentStorage {
    private var counter = 0
    val files = linkedSetOf<String>()
    val deleted = mutableListOf<String>()

    /** When set, saving a signature or photo fails like an unreadable image would. */
    var failSaving = false

    override suspend fun saveSignature(png: ByteArray): StoredFile {
        if (failSaving) throw java.io.IOException("disk full")
        return store("sig", "image/png", png.size.toLong())
    }

    override suspend fun savePhoto(sourceUri: String): StoredFile? =
        if (failSaving) null else store("photo", "image/jpeg", 1_000L)

    override fun newCaptureTarget(): CaptureTarget {
        val path = "/fake/capture_${counter++}.jpg"
        files += path
        return CaptureTarget(uri = "content://fake$path", path = path)
    }

    override suspend fun finishCapture(path: String): StoredFile? =
        if (path in files) StoredFile(path, "image/jpeg", 1_000L) else null

    override fun delete(path: String) {
        files -= path
        deleted += path
    }

    private fun store(prefix: String, mime: String, size: Long): StoredFile {
        val path = "/fake/${prefix}_${counter++}"
        files += path
        return StoredFile(path, mime, size)
    }
}

/** Collections kept in memory, the way the Room-backed repository would, so a test can see exactly what was saved. */
class InMemoryCboCollectionRepository : CboCollectionRepository {
    val saved = mutableListOf<Triple<CboCollectionEntity, List<ProductLineEntity>, List<CollectionAttachmentEntity>>>()
    var failWith: Exception? = null
    private val all = MutableStateFlow<List<CboCollectionEntity>>(emptyList())
    private val attachments = MutableStateFlow<List<CollectionAttachmentEntity>>(emptyList())

    override suspend fun save(
        collection: CboCollectionEntity,
        productLines: List<ProductLineEntity>,
        attachments: List<CollectionAttachmentEntity>
    ) {
        failWith?.let { throw it }
        saved += Triple(collection, productLines, attachments)
        all.value = all.value + collection
        this.attachments.value = this.attachments.value + attachments
    }

    override fun observeAll(): Flow<List<CboCollectionEntity>> = all
    override fun observeAttachments(): Flow<List<CollectionAttachmentEntity>> = attachments
    override fun observeCount(status: SyncStatus): Flow<Int> = all.map { l -> l.count { it.syncStatus == status } }
}

class CountingCboSyncTrigger : CboSyncTrigger {
    var calls = 0
    override fun syncCboCollectionsNow() {
        calls++
    }
}
