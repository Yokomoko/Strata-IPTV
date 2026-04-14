package com.strata.tv.ui.nav

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.hilt.navigation.compose.hiltViewModel
import com.strata.tv.data.tmdb.EnrichmentProgressTracker
import com.strata.tv.ui.home.HomeScreen
import com.strata.tv.ui.home.HomeViewModel
import com.strata.tv.ui.live.LiveScreen
import com.strata.tv.ui.movies.MovieDetailScreen
import com.strata.tv.ui.movies.MoviesScreen
import com.strata.tv.ui.player.PlayerScreen
import com.strata.tv.ui.search.SearchScreen
import com.strata.tv.ui.shows.ShowDetailScreen
import com.strata.tv.ui.shows.ShowsScreen
import com.strata.tv.ui.splash.SplashOverlay
import com.strata.tv.ui.theme.StrataColors

/**
 * Top-level shell -- sidebar + content area + overlay layers.
 *
 * Phase 9: now wires real screens for all destinations, plus
 * movie/show detail overlays and the player overlay.
 */
@Composable
fun Shell(
    nav: AppNavState = rememberAppNavState(),
    enrichmentTracker: EnrichmentProgressTracker? = null,
) {
    var sidebarHasFocus by remember { mutableStateOf(true) }
    val enrichmentProgress = enrichmentTracker?.progress?.collectAsState()?.value

    // -- Splash overlay state ----------------------------------------
    val homeViewModel: HomeViewModel = hiltViewModel()
    val homeState by homeViewModel.state.collectAsState()
    var firstLoadComplete by remember { mutableStateOf(enrichmentTracker == null) }

    // Dismiss on first data.
    LaunchedEffect(homeState) {
        if (homeState.movieCount > 0 || homeState.channelCount > 0 || homeState.seriesCount > 0) {
            firstLoadComplete = true
        }
    }

    // Max timeout: dismiss after 8 seconds regardless.
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(8_000)
        firstLoadComplete = true
    }

    LaunchedEffect(Unit) {
        runCatching { nav.sidebarRequester.requestFocus() }
    }

    // -- Back handler: detail overlay -> sidebar/content toggle -------
    BackHandler {
        if (!nav.navigateBack()) {
            if (sidebarHasFocus) {
                runCatching { nav.contentRequester.requestFocus() }
            } else {
                runCatching { nav.sidebarRequester.requestFocus() }
            }
        }
    }

    // -- Player overlay (highest z-order) ----------------------------
    val playerArgs = nav.playerArgs
    if (playerArgs != null) {
        PlayerScreen(
            streamUrl = playerArgs.streamUrl,
            title = playerArgs.title,
            isLive = playerArgs.isLive,
            resumePositionMs = playerArgs.resumePositionMs,
            contentType = playerArgs.contentType,
            artworkUrl = playerArgs.artworkUrl,
            channelList = playerArgs.channelList,
            currentChannelIndex = playerArgs.currentIndex,
            seriesTitle = playerArgs.seriesTitle,
            seasonNumber = playerArgs.seasonNumber,
            episodeNumber = playerArgs.episodeNumber,
            onExit = { nav.closePlayer() },
        )
        return // Player is full-screen, nothing else renders.
    }

    // -- Detail overlay (sits on top of content) ---------------------
    val detailRoute = nav.detailRoute
    if (detailRoute != null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(StrataColors.SurfaceVoid),
        ) {
            when (detailRoute) {
                is DetailRoute.Movie -> MovieDetailScreen(
                    contentId = detailRoute.contentId,
                    onBack = { nav.closeDetail() },
                    onPlay = { args -> nav.openPlayer(args) },
                )
                is DetailRoute.Show -> ShowDetailScreen(
                    seriesTitle = detailRoute.seriesTitle,
                    onBack = { nav.closeDetail() },
                    onPlay = { args -> nav.openPlayer(args) },
                )
            }
        }
        return // Detail is full-screen over the shell.
    }

    // -- Normal shell: sidebar + content -----------------------------
    Box(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .background(StrataColors.SurfaceVoid),
        ) {
            Sidebar(
                selected = nav.current,
                onSelected = { nav.navigate(it) },
                sidebarFocusRequester = nav.sidebarRequester,
                modifier = Modifier.onFocusChanged { sidebarHasFocus = it.hasFocus },
                enrichmentProgress = enrichmentProgress?.fraction ?: 0f,
                enrichmentRunning = enrichmentProgress?.isRunning == true,
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .focusRequester(nav.contentRequester)
                    .onFocusChanged { if (it.hasFocus) sidebarHasFocus = false },
            ) {
                when (nav.current) {
                    Destination.Home -> HomeScreen(onNavigate = nav)
                    Destination.Live -> LiveScreen(onNavigate = nav)
                    Destination.Movies -> MoviesScreen(onNavigate = nav)
                    Destination.Shows -> ShowsScreen(onNavigate = nav)
                    Destination.Search -> SearchScreen(onNavigate = nav)
                    Destination.Settings -> SettingsPlaceholder()
                }
            }
        }

        // -- Splash overlay (fades out once data arrives) ---------------
        AnimatedVisibility(
            visible = !firstLoadComplete,
            exit = fadeOut(animationSpec = tween(durationMillis = 500)),
        ) {
            SplashOverlay(
                enrichmentFraction = enrichmentProgress?.fraction ?: 0f,
                enrichmentRunning = enrichmentProgress?.isRunning == true,
            )
        }
    }
}

/** Temporary placeholder for Settings. */
@Composable
private fun SettingsPlaceholder() {
    // Settings screen exists in ui/settings/ but does not need Phase 9 treatment.
    com.strata.tv.ui.settings.SettingsScreen()
}
