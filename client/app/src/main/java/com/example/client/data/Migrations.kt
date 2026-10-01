package com.example.client.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** v2 -> v3: cbo_collections.syncErrorCode, the reason the last sync attempt for a record failed. */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE cbo_collections ADD COLUMN syncErrorCode TEXT")
    }
}
