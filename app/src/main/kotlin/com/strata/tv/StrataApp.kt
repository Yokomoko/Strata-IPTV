package com.strata.tv

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Application root.
 *
 * Hilt's [HiltAndroidApp] entry point — required so the DI graph is
 * available to every Activity / ViewModel / Worker in the app.
 *
 * Implements [Configuration.Provider] so that WorkManager uses the
 * [HiltWorkerFactory] to create @HiltWorker-annotated workers with
 * their injected dependencies.
 */
@HiltAndroidApp
class StrataApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
