package com.example.client.di

import android.content.Context
import androidx.room.Room
import com.example.client.data.AppDatabase
import com.example.client.data.MIGRATION_2_3
import com.example.client.data.MIGRATION_3_4
import com.example.client.data.MIGRATION_4_5
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
        .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
        // TODO(sprint-4): replace with real migration before release
        .fallbackToDestructiveMigration()
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
