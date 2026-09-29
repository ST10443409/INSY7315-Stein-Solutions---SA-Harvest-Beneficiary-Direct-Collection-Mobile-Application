package com.example.client

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Application entry point. [HiltAndroidApp] triggers Hilt code generation and
 * hosts the application-level (SingletonComponent) dependency container.
 */
@HiltAndroidApp
class SaHarvestApplication : Application()
