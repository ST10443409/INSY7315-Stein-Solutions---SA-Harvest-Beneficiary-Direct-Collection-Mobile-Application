package com.example.client.di

import android.content.Context
import androidx.room.Room
import com.example.client.data.AppDatabase
import com.example.client.data.ALL_MIGRATIONS
import com.example.client.data.FIRST_MIGRATED_VERSION
import com.example.client.data.SyncPayloadDao
import com.example.client.data.local.dao.*
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "app_database"
        )
        .addMigrations(*ALL_MIGRATIONS)
        // Only the pre-release version 1 may be wiped. From version 2 on, every upgrade has a tested migration: a
        // destructive fallback would silently delete collections and decisions that have not synced yet (#55).
        .fallbackToDestructiveMigrationFrom(FIRST_MIGRATED_VERSION - 1)
        .build()
    }

    @Provides
    fun provideSyncPayloadDao(database: AppDatabase): SyncPayloadDao = database.syncPayloadDao()

    @Provides
    fun provideCboDao(database: AppDatabase): CboDao = database.cboDao()

    @Provides
    fun provideCboCollectionDao(database: AppDatabase): CboCollectionDao = database.cboCollectionDao()

    @Provides
    fun provideProductLineDao(database: AppDatabase): ProductLineDao = database.productLineDao()

    @Provides
    fun provideFoodspaceBeneficiaryDao(database: AppDatabase): FoodspaceBeneficiaryDao = database.foodspaceBeneficiaryDao()

    @Provides
    fun provideVettingDecisionDao(database: AppDatabase): VettingDecisionDao = database.vettingDecisionDao()
}
