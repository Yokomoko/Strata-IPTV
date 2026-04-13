package com.strata.tv.ui.nav

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import com.strata.tv.ui.home.HomeScreen
import com.strata.tv.ui.live.LiveScreen
import com.strata.tv.ui.movies.MoviesScreen
import com.strata.tv.ui.player.PlayerScreen
import com.strata.tv.ui.search.SearchScreen
import com.strata.tv.ui.settings.SettingsScreen
import com.strata.tv.ui.shows.ShowsScreen
import com.strata.tv.ui.theme.StrataColors

/**
 * Top-level shell — renders the persistent sidebar on the left and
 * the currently-selected destination's screen on the right.
 *
 * When a screen asks to play something (movies, shows, live channels,
 * search results) it drops a [PlayerArgs] into [AppNavState.playerArgs]
 * and this shell overlays [PlayerScreen] full-bleed on top of the
 * selected tab.  Back from the player clears the overlay and reveals
 * the original tab exactly as the user left it.
 *
 * Focus model:
 * - On launch, the sidebar autofocuses (LaunchedEffect on first frame).
 * - D-pad Right from the sidebar enters the content area via the
 *   sidebar's exit-direction focus property.
 * - The back button toggles focus between sidebar and content — or
 *   closes the player if one is open.
 */
@Composable
fun Shell(
    nav: AppNavState = rememberAppNavState(),
) {
    // Track which zone last had focus so the back-button toggle
    // knows whether to send focus to sidebar or content.
    var sidebarHasFocus by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        // First-frame autofocus on the sidebar — sets the user's
        // entry point on app launch.
        runCatching { nav.sidebarRequester.requestFocus() }
    }

    BackHandler {
        when {
            // Player open → close it and reveal the tab underneath.
            nav.playerArgs != null -> nav.closePlayer()

            // Sidebar has focus → push focus into the content area.
            // (Same gesture as Fire Stick remote's Back when the
            // user is parked on the sidebar — they want to keep
            // watching what's shown, not exit.)
            sidebarHasFocus -> runCatching { nav.contentRequester.requestFocus() }

            // Content has focus → bounce back to sidebar.
            else -> runCatching { nav.sidebarRequester.requestFocus() }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(StrataColors.SurfaceVoid),
    ) {
        // -- Base layer: sidebar + content ---------------------------------
        Row(modifier = Modifier.fillMaxSize()) {
            Sidebar(
                selected = nav.current,
                onSelected = { nav.navigate(it) },
                sidebarFocusRequester = nav.sidebarRequester,
                modifier = Modifier.onFocusChanged { sidebarHasFocus = it.hasFocus },
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .focusRequester(nav.contentRequester)
                    .onFocusChanged { if (it.hasFocus) sidebarHasFocus = false },
            ) {
                when (nav.current) {
                    Destination.Home -> HomeScreen()
                    Destination.Live -> LiveScreen(
                        onPlayChannel = { channel ->
                            nav.openPlayer(
                                PlayerArgs(
                                    streamUrl = channel.streamUrl,
                                    title = channel.displayName,
                                    isLive = true,
                                    contentType = "live",
                                    artworkUrl = channel.logoUrl,
                                ),
                            )
                        },
                    )
                    Destination.Movies -> MoviesScreen(onPlay = nav::openPlayer)
                    Destination.Shows -> ShowsScreen(onPlay = nav::openPlayer)
                    Destination.Search -> SearchScreen(
                        onResultClick = { result ->
                            nav.openPlayer(
                                PlayerArgs(
                                    streamUrl = result.streamUrl,
                                    title = result.title.ifBlank { result.displayName },
                                    isLive = result.contentType == "live",
                                    contentType = result.contentType,
                                    artworkUrl = result.artworkUrl,
                                ),
                            )
                        },
                    )
                    Destination.Settings -> SettingsScreen()
                }
            }
        }

        // -- Overlay layer: full-bleed player -----------------------------
        nav.playerArgs?.let { args ->
            PlayerScreen(
                streamUrl = args.streamUrl,
                title = args.title,
                isLive = args.isLive,
                resumePositionMs = args.resumePositionMs,
                contentType = args.contentType,
                artworkUrl = args.artworkUrl,
                onExit = { nav.closePlayer() },
            )
        }
    }
}
