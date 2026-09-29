package com.example.client.di

import com.example.client.auth.EncryptedTokenStorage
import com.example.client.auth.RoleProvider
import com.example.client.auth.SessionManager
import com.example.client.auth.TokenStorage
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AuthModule {

    @Binds
    @Singleton
    abstract fun bindTokenStorage(impl: EncryptedTokenStorage): TokenStorage

    // The navigation shell's role comes from the real session now (was a stub, #26).
    @Binds
    abstract fun bindRoleProvider(impl: SessionManager): RoleProvider
}
