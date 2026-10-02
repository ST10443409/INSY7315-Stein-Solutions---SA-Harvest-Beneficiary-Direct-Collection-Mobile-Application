package com.example.client.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** v2 -> v3: cbo_collections.syncErrorCode, the reason the last sync attempt for a record failed. */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE cbo_collections ADD COLUMN syncErrorCode TEXT")
    }
}

/** v3 -> v4: vetting_decisions.retryCount and .syncErrorCode, the sync bookkeeping the Vetting sync worker keeps. */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Existing decisions have never failed a sync: zero retries used, no error.
        db.execSQL("ALTER TABLE vetting_decisions ADD COLUMN retryCount INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE vetting_decisions ADD COLUMN syncErrorCode TEXT")
    }
}

/**
 * v4 -> v5: collection_attachments, the signatures and photos captured for a collection (Form 1). Existing collections
 * have none, so there is nothing to copy; the table starts empty.
 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `collection_attachments` (`id` TEXT NOT NULL, `collectionId` TEXT NOT NULL, " +
                "`kind` TEXT NOT NULL, `slot` INTEGER NOT NULL, `filePath` TEXT NOT NULL, `mimeType` TEXT NOT NULL, " +
                "`sizeBytes` INTEGER NOT NULL, `syncStatus` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_collection_attachments_collectionId` ON `collection_attachments` (`collectionId`)")
    }
}
