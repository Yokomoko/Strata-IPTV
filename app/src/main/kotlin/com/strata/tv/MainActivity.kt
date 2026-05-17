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
 * see it, so we override [dispatchKeyEvent] to route it to our Search tab.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var enrichmentTracker: EnrichmentProgressTracker

    /** Retained across recompositions so dispatchKeyEvent can poke the nav. */
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

    /**
     * Intercept ALL key events for search/voice/assist buttons so that
     * Fire OS never sees them — not on DOWN, not on UP, not on long-press.
     * The previous [onKeyDown] override only caught ACTION_DOWN which let
     * long-press and ACTION_UP leak through to the system search overlay.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode in setOf(
                KeyEvent.KEYCODE_SEARCH,
                KeyEvent.KEYCODE_VOICE_ASSIST,
                KeyEvent.KEYCODE_ASSIST,
            )
        ) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                navState?.let { nav ->
                    nav.navigate(Destination.Search)
                    runCatching { nav.contentRequester.requestFocus() }
                }
            }
            return true // consume both DOWN and UP — block Fire OS search
        }
        return super.dispatchKeyEvent(event)
    }
}
