package com.example.client.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

import androidx.room.TypeConverters
import com.example.client.data.local.converter.Converters
import com.example.client.data.local.entity.CboCollectionEntity
import com.example.client.data.local.entity.CboEntity
import com.example.client.data.local.entity.ProductLineEntity

import com.example.client.data.local.entity.FoodspaceBeneficiaryRecord
import com.example.client.data.local.entity.VettingDecision

import com.example.client.data.local.dao.CboDao
import com.example.client.data.local.dao.CboCollectionDao
import com.example.client.data.local.dao.ProductLineDao
import com.example.client.data.local.dao.FoodspaceBeneficiaryDao
import com.example.client.data.local.dao.VettingDecisionDao

@Database(
    entities = [
        SyncPayload::class,
        CboEntity::class,
        CboCollectionEntity::class,
        ProductLineEntity::class,
        FoodspaceBeneficiaryRecord::class,
        VettingDecision::class
    ], 
    version = 4,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun syncPayloadDao(): SyncPayloadDao
    abstract fun cboDao(): CboDao
    abstract fun cboCollectionDao(): CboCollectionDao
    abstract fun productLineDao(): ProductLineDao
    abstract fun foodspaceBeneficiaryDao(): FoodspaceBeneficiaryDao
    abstract fun vettingDecisionDao(): VettingDecisionDao

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
