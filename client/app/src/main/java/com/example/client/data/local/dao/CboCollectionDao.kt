package com.example.client.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.example.client.data.local.entity.CboCollectionEntity
import com.example.client.data.local.entity.CollectionAttachmentEntity
import com.example.client.data.local.entity.ProductLineEntity
import com.example.client.data.local.entity.SyncStatus
import kotlinx.coroutines.flow.Flow

@Dao
abstract class CboCollectionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insert(collection: CboCollectionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertProductLines(productLines: List<ProductLineEntity>)

    /** Writes a collection and its product lines atomically, so one is never stored without the other. */
    @Transaction
    open suspend fun insertWithProductLines(
        collection: CboCollectionEntity,
        productLines: List<ProductLineEntity>
    ) {
        insert(collection)
        insertProductLines(productLines)
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertAttachments(attachments: List<CollectionAttachmentEntity>)

    /** Writes a collection with its product lines and captured signatures and photos atomically. */
    @Transaction
    open suspend fun insertWithChildren(
        collection: CboCollectionEntity,
        productLines: List<ProductLineEntity>,
        attachments: List<CollectionAttachmentEntity>
    ) {
        insertWithProductLines(collection, productLines)
        if (attachments.isNotEmpty()) insertAttachments(attachments)
    }

    /** The signatures and photos stored for the given collections. */
    @Query("SELECT * FROM collection_attachments WHERE collectionId IN (:collectionIds)")
    abstract suspend fun getAttachmentsForCollections(collectionIds: List<String>): List<CollectionAttachmentEntity>

    /** Every signature and photo stored on this device. Updates live. */
    @Query("SELECT * FROM collection_attachments ORDER BY createdAt ASC")
    abstract fun observeAttachments(): Flow<List<CollectionAttachmentEntity>>

    @Update
    abstract suspend fun update(collection: CboCollectionEntity)

    @Query("SELECT * FROM cbo_collections WHERE syncStatus = :status")
    abstract fun getBySyncStatus(status: SyncStatus): Flow<List<CboCollectionEntity>>

    @Query("SELECT * FROM cbo_collections")
    abstract fun getAll(): Flow<List<CboCollectionEntity>>

    /** All locally stored collections, most recent submission first. */
    @Query("SELECT * FROM cbo_collections ORDER BY createdAt DESC")
    abstract fun observeAllNewestFirst(): Flow<List<CboCollectionEntity>>

    /**
     * The collections [author] captured, most recent submission first. What a person sees of their own work on a phone that
     * other people also sign in to (#70). A record saved before authors were kept (NULL) shows for everyone, as it can be sent
     * by anyone. Usernames are compared ignoring case, as the server does at sign-in.
     */
    @Query(
        "SELECT * FROM cbo_collections WHERE authorUsername = :author COLLATE NOCASE OR authorUsername IS NULL " +
            "ORDER BY createdAt DESC"
    )
    abstract fun observeByAuthorNewestFirst(author: String?): Flow<List<CboCollectionEntity>>

    /**
     * What the sync worker should send for [author]: their PENDING records, plus their FAILED ones that still have retries
     * left. Never another user's: the server attributes a record to whoever sends it (#70). Records with no recorded author
     * (saved before it was kept) are sent by whoever is signed in.
     */
    @Query(
        "SELECT * FROM cbo_collections WHERE (syncStatus = 'PENDING' OR (syncStatus = 'FAILED' AND retryCount < :maxRetries)) " +
            "AND (authorUsername = :author COLLATE NOCASE OR authorUsername IS NULL) ORDER BY createdAt ASC"
    )
    abstract suspend fun getSyncable(maxRetries: Int, author: String): List<CboCollectionEntity>

    @Query("UPDATE cbo_collections SET syncStatus = 'SYNCED', syncErrorCode = NULL, updatedAt = :now WHERE id IN (:ids)")
    abstract suspend fun markSynced(ids: List<String>, now: Long)

    /** A failure that may heal on its own: uses up one retry, and the record is sent again on a later run. */
    @Query(
        "UPDATE cbo_collections SET syncStatus = 'FAILED', retryCount = retryCount + 1, " +
            "syncErrorCode = :errorCode, updatedAt = :now WHERE id IN (:ids)"
    )
    abstract suspend fun markFailed(ids: List<String>, errorCode: String?, now: Long)

    /**
     * A failure resending cannot fix (the server said so): the record is FAILED with all [maxRetries] used up,
     * so [getSyncable] skips it from now on. Its data stays on the device.
     */
    @Query(
        "UPDATE cbo_collections SET syncStatus = 'FAILED', retryCount = :maxRetries, " +
            "syncErrorCode = :errorCode, updatedAt = :now WHERE id IN (:ids)"
    )
    abstract suspend fun markRejected(ids: List<String>, errorCode: String?, maxRetries: Int, now: Long)

    @Query("SELECT COUNT(*) FROM cbo_collections WHERE syncStatus = :status")
    abstract fun observeCountByStatus(status: SyncStatus): Flow<Int>

    /**
     * How many records some other account captured that are still waiting to be sent: they stay on this phone until their
     * author signs in (#70), and whoever is signed in now is told so rather than left to wonder where they went.
     */
    @Query(
        "SELECT COUNT(*) FROM cbo_collections WHERE (syncStatus = 'PENDING' OR (syncStatus = 'FAILED' AND retryCount < :maxRetries)) " +
            "AND authorUsername IS NOT NULL AND authorUsername <> :author COLLATE NOCASE"
    )
    abstract fun observeWaitingForOtherAuthors(author: String?, maxRetries: Int): Flow<Int>

    // ── Uploading the signatures and photos ──────────────────────────────────────────

    /**
     * The files [author] still has to upload: PENDING ones, plus FAILED ones with retries left, of collections the server
     * already has (SYNCED, or held by the server as a suspected duplicate for an Admin to review, whose pictures are what
     * the reviewer needs). A file is never uploaded before its record: the server refuses a file for a collection it has not
     * received. Oldest first. Same author rule as [getSyncable].
     */
    @Query(
        "SELECT a.* FROM collection_attachments a JOIN cbo_collections c ON c.id = a.collectionId " +
            "WHERE (c.syncStatus = 'SYNCED' OR (c.syncStatus = 'FAILED' AND c.syncErrorCode = 'DUPLICATE_DETECTED')) " +
            "AND (a.syncStatus = 'PENDING' OR (a.syncStatus = 'FAILED' AND a.retryCount < :maxRetries)) " +
            "AND (c.authorUsername = :author COLLATE NOCASE OR c.authorUsername IS NULL) ORDER BY a.createdAt ASC"
    )
    abstract suspend fun getUploadableAttachments(maxRetries: Int, author: String): List<CollectionAttachmentEntity>

    @Query("UPDATE collection_attachments SET syncStatus = 'SYNCED', syncErrorCode = NULL, updatedAt = :now WHERE id IN (:ids)")
    abstract suspend fun markAttachmentsUploaded(ids: List<String>, now: Long)

    /** A refusal resending cannot fix: FAILED with every retry used up, so [getUploadableAttachments] skips it from now on. */
    @Query(
        "UPDATE collection_attachments SET syncStatus = 'FAILED', retryCount = :maxRetries, syncErrorCode = :errorCode, " +
            "updatedAt = :now WHERE id IN (:ids)"
    )
    abstract suspend fun markAttachmentsRejected(ids: List<String>, errorCode: String?, maxRetries: Int, now: Long)
}
