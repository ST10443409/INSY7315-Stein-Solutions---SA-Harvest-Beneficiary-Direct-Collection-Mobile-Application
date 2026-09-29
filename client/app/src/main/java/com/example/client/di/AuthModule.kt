package com.example.client.di

import com.example.client.auth.RoleProvider
import com.example.client.auth.StubRoleProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AuthModule {

    // TODO(#27): bind the real token-backed RoleProvider instead of the stub.
    @Binds
    @Singleton
    abstract fun bindRoleProvider(impl: StubRoleProvider): RoleProvider
}
