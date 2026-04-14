package com.strata.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.strata.tv.data.tmdb.EnrichmentProgressTracker
import com.strata.tv.ui.nav.Shell
import com.strata.tv.ui.theme.StrataTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Single-activity host for the Compose tree.
 *
 * Phase 2 onward: renders the persistent [Shell] (sidebar + content
 * area).  Per-screen state lives in ViewModels injected by Hilt;
 * this activity is intentionally a thin wrapper.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var enrichmentTracker: EnrichmentProgressTracker

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            StrataTheme {
                Shell(enrichmentTracker = enrichmentTracker)
            }
        }
    }
}
