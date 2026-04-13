package com.strata.tv

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Application root.
 *
 * Hilt's [HiltAndroidApp] entry point — required so the DI graph is
 * available to every Activity / ViewModel / Worker in the app. We
 * deliberately keep this class small; one-time initialisation (Coil
 * config, Logger setup) lives in dedicated `Initialiser` classes wired
 * via [App.Startup] in later phases.
 */
@HiltAndroidApp
class StrataApp : Application()
