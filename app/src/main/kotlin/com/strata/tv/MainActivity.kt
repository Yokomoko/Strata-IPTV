package com.strata.tv

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.strata.tv.data.tmdb.EnrichmentProgressTracker
import com.strata.tv.ui.nav.AppNavState
import com.strata.tv.ui.nav.Destination
import com.strata.tv.ui.nav.Shell
import com.strata.tv.ui.nav.rememberAppNavState
import com.strata.tv.ui.theme.StrataTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Single-activity host for the Compose tree.
 *
 * Intercepts the Fire Stick remote's search button at the Activity
 * level — Fire OS grabs KEYCODE_SEARCH before Compose's key handlers
 * see it, so we override [onKeyDown] to route it to our Search tab.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var enrichmentTracker: EnrichmentProgressTracker

    /** Retained across recompositions so onKeyDown can poke the nav. */
    private var navState: AppNavState? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            StrataTheme {
                val nav = rememberAppNavState()
                navState = nav
                Shell(nav = nav, enrichmentTracker = enrichmentTracker)
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_SEARCH) {
            navState?.let { nav ->
                nav.navigate(Destination.Search)
                runCatching { nav.contentRequester.requestFocus() }
            }
            return true // consume — don't let Fire OS open system search
        }
        return super.onKeyDown(keyCode, event)
    }
}
