package com.example.client.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [SyncPayload::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun syncPayloadDao(): SyncPayloadDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                // For demonstration, using in-memory database as requested
                // "When the device is offline, the memory will be stored in a local (in memory )RoomDB"
                val instance = Room.inMemoryDatabaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
