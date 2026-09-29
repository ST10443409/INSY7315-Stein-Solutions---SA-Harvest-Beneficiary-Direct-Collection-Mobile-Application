package com.example.client.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Placeholder for network dependencies (OkHttpClient, Retrofit, API services).
 * TODO: migrate the bindings currently held by RetrofitClient into this module.
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule
