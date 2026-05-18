package com.strata.tv.ui.nav

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Text
import com.strata.tv.data.tmdb.EnrichmentProgressTracker
import com.strata.tv.ui.setup.SetupWizardScreen
import com.strata.tv.ui.setup.ShellGateViewModel
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
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

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
    gateViewModel: ShellGateViewModel = hiltViewModel(),
) {
    // Instantiate HomeViewModel here (even before the gate) so its
    // init block fires — that's what kicks off the very first sync.
    // Without this, the Wizard finishes, gate switches to FirstSync,
    // SyncProgressScreen renders, but nothing actually syncs because
    // HomeViewModel never gets created.
    val homeViewModel: HomeViewModel = hiltViewModel()

    // Gate: until the user has configured a provider AND the first
    // sync has either completed or been backgrounded, replace the
    // whole UI with the wizard or the sync progress screen.
    val stage by gateViewModel.stage.collectAsState()
    when (stage) {
        null -> {
            // Brief loading state while DataStore is read.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(StrataColors.SurfaceVoid),
            ) {}
            return
        }
        com.strata.tv.ui.setup.GateStage.Wizard -> {
            SetupWizardScreen(onFinished = { /* StateFlow re-emits, gate updates */ })
            return
        }
        com.strata.tv.ui.setup.GateStage.FirstSync -> {
            val syncProgress by gateViewModel.syncService.progress.collectAsState()
            val enrichProgress by gateViewModel.enrichmentTracker.progress.collectAsState()
            com.strata.tv.ui.setup.SyncProgressScreen(
                progress = syncProgress,
                enrichment = enrichProgress,
                onSkipToBackground = { gateViewModel.skipToBackground() },
            )
            return
        }
        com.strata.tv.ui.setup.GateStage.Main -> Unit // continue to normal shell
    }

    var sidebarHasFocus by remember { mutableStateOf(true) }
    val enrichmentProgress = enrichmentTracker?.progress?.collectAsState()?.value

    // -- Splash overlay state ----------------------------------------
    // homeViewModel already instantiated above (before the gate) so
    // its init block has fired and the first sync is in flight.
    val homeState by homeViewModel.state.collectAsState()
    // Suppress the splash entirely if the user just came through the
    // first-run wizard / sync progress screen — they've already sat
    // through a loading UI, a second one would be tedious.  On
    // subsequent app launches `passedThroughGate` stays false and the
    // splash plays its usual 8-second startup-sound bridge.
    val passedThroughGate by gateViewModel.passedThroughGate.collectAsState()
    var firstLoadComplete by remember(passedThroughGate) {
        mutableStateOf(enrichmentTracker == null || passedThroughGate)
    }

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
            contentId = playerArgs.contentId,
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

        // -- Floating clock at the top-right of the shell --------------
        // Sits above the content area, not the sidebar, so it stays out
        // of the way of D-pad navigation but is always visible no matter
        // which destination is active.
        ShellClock(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 16.dp, end = 24.dp),
        )

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

/**
 * Date + time pill rendered at the top of the shell.  Re-ticks every
 * minute (we display minute precision so anything finer is wasted
 * draws).  The `tick` state is keyed by the minute of the wall clock,
 * so the formatter only re-runs when the visible string would change.
 *
 * Format: "Mon 5 May  21:34" — day abbreviation + date + 24h time, with
 * the time in semi-bold so it reads at a glance.
 */
@Composable
private fun ShellClock(modifier: Modifier = Modifier) {
    var now by remember { mutableStateOf(LocalDateTime.now()) }

    // Wake up at the top of every minute; an absolute delay against
    // the wall clock keeps the tick aligned even if the device was
    // backgrounded mid-minute and Compose's scheduler drifted.
    LaunchedEffect(Unit) {
        while (true) {
            now = LocalDateTime.now()
            // Sleep until the next whole minute (60s minus the current
            // second offset) so the displayed value flips exactly when
            // the wall clock does.
            val millisIntoMinute = now.second * 1_000L + (now.nano / 1_000_000L)
            delay(60_000L - millisIntoMinute)
        }
    }

    // Pre-built once per render; LocalDateTime values cache cheaply.
    val dateFmt = remember { DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()) }
    val timeFmt = remember { DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault()) }

    val display = remember(now) {
        buildAnnotatedString {
            withStyle(SpanStyle(color = StrataColors.TextTertiary)) {
                append(now.format(dateFmt))
                append("  ")
            }
            withStyle(
                SpanStyle(
                    color = StrataColors.TextSecondary,
                    fontWeight = FontWeight.SemiBold,
                ),
            ) {
                append(now.format(timeFmt))
            }
        }
    }

    Text(
        text = display,
        fontSize = 12.sp,
        modifier = modifier,
    )
}

/** Temporary placeholder for Settings. */
@Composable
private fun SettingsPlaceholder() {
    // Settings screen exists in ui/settings/ but does not need Phase 9 treatment.
    com.strata.tv.ui.settings.SettingsScreen()
}
