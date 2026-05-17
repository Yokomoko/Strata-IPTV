package com.strata.tv.testing

import androidx.activity.ComponentActivity
import dagger.hilt.android.AndroidEntryPoint

/**
 * Bare `@AndroidEntryPoint` activity used by Compose UI tests so
 * Hilt-injected ViewModels resolve when we call
 * `composeTestRule.setContent { Screen(...) }`.
 *
 * Must be declared in `androidTest/AndroidManifest.xml` so the merged
 * test APK includes it.
 */
@AndroidEntryPoint
class HiltTestActivity : ComponentActivity()
