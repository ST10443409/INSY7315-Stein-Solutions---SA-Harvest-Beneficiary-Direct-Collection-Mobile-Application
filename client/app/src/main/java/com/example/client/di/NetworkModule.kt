package com.example.client.di

import com.example.client.BuildConfig
import com.example.client.network.AdminApiService
import com.example.client.network.AttachmentApiService
import com.example.client.network.AuthApiService
import com.example.client.network.AuthInterceptor
import com.example.client.network.HttpClients
import com.example.client.network.SessionAuthenticator
import com.example.client.network.SyncApiService
import com.example.client.network.VettingApiService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    // The API address comes from the build (see API_BASE_URL in app/build.gradle.kts), not from code.

    @Provides
    @Singleton
    fun provideOkHttpClient(
        authInterceptor: AuthInterceptor,
        sessionAuthenticator: SessionAuthenticator
    ): OkHttpClient {
        // Timeouts and request compression for slow links come from HttpClients (#55).
        val builder = HttpClients.builder()
            .addInterceptor(authInterceptor)
            .authenticator(sessionAuthenticator)
        // Debug builds only, and BASIC (no bodies), so credentials and tokens never reach logcat; header redacted as well.
        // Release builds log nothing: even URLs carry search terms such as a username (#54).
        if (BuildConfig.DEBUG) {
            builder.addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
                redactHeader(AuthInterceptor.AUTHORIZATION)
            })
        }
        return builder.build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(client: OkHttpClient): Retrofit = Retrofit.Builder()
        .baseUrl(BuildConfig.API_BASE_URL)
        .addConverterFactory(GsonConverterFactory.create())
        .client(client)
        .build()

    @Provides
    @Singleton
    fun provideSyncApiService(retrofit: Retrofit): SyncApiService =
        retrofit.create(SyncApiService::class.java)

    @Provides
    @Singleton
    fun provideAttachmentApiService(retrofit: Retrofit): AttachmentApiService =
        retrofit.create(AttachmentApiService::class.java)

    @Provides
    @Singleton
    fun provideAuthApiService(retrofit: Retrofit): AuthApiService =
        retrofit.create(AuthApiService::class.java)

    @Provides
    @Singleton
    fun provideVettingApiService(retrofit: Retrofit): VettingApiService =
        retrofit.create(VettingApiService::class.java)

    @Provides
    @Singleton
    fun provideAdminApiService(retrofit: Retrofit): AdminApiService =
        retrofit.create(AdminApiService::class.java)
}
