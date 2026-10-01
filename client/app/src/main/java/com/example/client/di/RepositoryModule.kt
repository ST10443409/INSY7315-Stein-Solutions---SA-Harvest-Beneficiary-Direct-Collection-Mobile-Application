package com.example.client.di

import com.example.client.auth.AuthRepository
import com.example.client.auth.AuthRepositoryImpl
import com.example.client.data.repository.CboCollectionRepository
import com.example.client.data.repository.CboCollectionRepositoryImpl
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
}
