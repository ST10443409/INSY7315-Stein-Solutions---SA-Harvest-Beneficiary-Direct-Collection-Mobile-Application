package com.example.client.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/** What a [CollectionAttachmentEntity] is a picture of. */
enum class AttachmentKind {
    /** The donor's finger-drawn signature (at most one per collection). */
    DONOR_SIGNATURE,

    /** The CBO's finger-drawn signature (at most one per collection). */
    CBO_SIGNATURE,

    /** One of the donation photos; [CollectionAttachmentEntity.slot] says which of the numbered shots it is. */
    PHOTO,

    /** The photo of the paper delivery note (at most one per collection). */
    DELIVERY_NOTE
}

/**
 * A signature or photo captured for a CBO collection (Form 1).
 *
 * The picture itself is a file in the app's private storage and this row is its record in the database: [filePath] says
 * where it is, so it stays with the collection when the collection is queued for sync. (Image bytes are deliberately not
 * kept in the row: Room reads a row through a ~2 MB window, which a photo can exceed.)
 *
 * Uploading the file to cloud storage is not built yet, so [syncStatus] stays PENDING; it exists so the upload can be
 * added without another migration.
 */
@Entity(tableName = "collection_attachments", indices = [Index("collectionId")])
data class CollectionAttachmentEntity(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),

    // The CboCollectionEntity this belongs to (conceptually; like product lines, no enforced foreign key).
    val collectionId: String,

    val kind: AttachmentKind,

    // Which numbered photo this is when [kind] is PHOTO; 0 for every other kind.
    val slot: Int = 0,

    val filePath: String,
    val mimeType: String,
    val sizeBytes: Long,

    val syncStatus: SyncStatus = SyncStatus.PENDING,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
