package com.example.client.di

import com.example.client.auth.AuthRepository
import com.example.client.auth.AuthRepositoryImpl
import com.example.client.data.repository.AdminSyncResolutionRepository
import com.example.client.data.repository.AdminSyncResolutionRepositoryImpl
import com.example.client.data.repository.AdminSyncStatusRepository
import com.example.client.data.repository.AdminSyncStatusRepositoryImpl
import com.example.client.data.repository.AdminUserActivityRepository
import com.example.client.data.repository.AdminUserActivityRepositoryImpl
import com.example.client.data.repository.CboCollectionRepository
import com.example.client.data.repository.CboCollectionRepositoryImpl
import com.example.client.data.repository.PrefsRecordsMetaStore
import com.example.client.data.repository.RecordsMetaStore
import com.example.client.data.repository.VettingRecordsRepository
import com.example.client.data.repository.VettingRecordsRepositoryImpl
import com.example.client.data.repository.VettingRepository
import com.example.client.data.repository.VettingRepositoryImpl
import com.example.client.sync.CboSyncTrigger
import com.example.client.sync.VettingSyncTrigger
import com.example.client.sync.SyncScheduler
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindAuthRepository(impl: AuthRepositoryImpl): AuthRepository

    @Binds
    @Singleton
    abstract fun bindCboCollectionRepository(impl: CboCollectionRepositoryImpl): CboCollectionRepository

    @Binds
    abstract fun bindCboSyncTrigger(impl: SyncScheduler): CboSyncTrigger

    @Binds
    abstract fun bindVettingSyncTrigger(impl: SyncScheduler): VettingSyncTrigger

    @Binds
    @Singleton
    abstract fun bindVettingRecordsRepository(impl: VettingRecordsRepositoryImpl): VettingRecordsRepository

    @Binds
    @Singleton
    abstract fun bindVettingRepository(impl: VettingRepositoryImpl): VettingRepository

    @Binds
    @Singleton
    abstract fun bindRecordsMetaStore(impl: PrefsRecordsMetaStore): RecordsMetaStore

    @Binds
    @Singleton
    abstract fun bindAdminSyncResolutionRepository(impl: AdminSyncResolutionRepositoryImpl): AdminSyncResolutionRepository

    @Binds
    @Singleton
    abstract fun bindAdminUserActivityRepository(impl: AdminUserActivityRepositoryImpl): AdminUserActivityRepository

    @Binds
    @Singleton
    abstract fun bindAdminSyncStatusRepository(impl: AdminSyncStatusRepositoryImpl): AdminSyncStatusRepository
}
