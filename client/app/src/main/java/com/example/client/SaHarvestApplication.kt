package com.example.client

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.example.client.sync.SyncScheduler
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Application entry point. [HiltAndroidApp] triggers Hilt code generation and
 * hosts the application-level (SingletonComponent) dependency container.
 * Also supplies WorkManager's configuration so workers can be Hilt-injected.
 */
@HiltAndroidApp
class SaHarvestApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var syncScheduler: SyncScheduler

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        syncScheduler.scheduleCboCollectionsPeriodic()
    }
}
