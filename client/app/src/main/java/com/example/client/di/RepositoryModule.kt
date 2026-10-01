package com.example.client.di

import com.example.client.auth.AuthRepository
import com.example.client.auth.AuthRepositoryImpl
import com.example.client.data.repository.CboCollectionRepository
import com.example.client.data.repository.CboCollectionRepositoryImpl
import com.example.client.sync.CboSyncTrigger
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
}
