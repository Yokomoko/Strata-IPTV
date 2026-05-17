package com.strata.tv

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import dagger.hilt.android.testing.HiltTestApplication

/**
 * Custom test runner that swaps the production [StrataApp] for
 * [HiltTestApplication] so `@AndroidEntryPoint` / `@HiltViewModel`
 * bindings resolve correctly inside instrumented tests.
 *
 * Referenced from `app/build.gradle.kts` via
 * `testInstrumentationRunner = "com.strata.tv.HiltTestRunner"`.
 */
class HiltTestRunner : AndroidJUnitRunner() {
    override fun newApplication(
        cl: ClassLoader?,
        className: String?,
        context: Context?,
    ): Application = super.newApplication(cl, HiltTestApplication::class.java.name, context)
}
