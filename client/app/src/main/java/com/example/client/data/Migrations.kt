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
