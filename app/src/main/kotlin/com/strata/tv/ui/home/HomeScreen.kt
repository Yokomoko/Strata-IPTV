package com.strata.tv.ui.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.focusable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import android.view.KeyEvent
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.strata.tv.data.db.ContinueWatchingEntity
import com.strata.tv.data.db.SeriesEntity
import com.strata.tv.data.db.WatchlistEntity
import com.strata.tv.data.db.MovieEntity
import com.strata.tv.data.db.MovieListItem
import com.strata.tv.data.repo.SyncService
import com.strata.tv.ui.nav.AppNavState
import com.strata.tv.ui.theme.StrataColors
import com.strata.tv.ui.widgets.CardContextMenu
import com.strata.tv.ui.widgets.ContextMenuAction
import com.strata.tv.ui.widgets.PosterCard
import com.strata.tv.ui.widgets.Rail
import com.strata.tv.ui.widgets.ShimmerRail
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Home screen -- Netflix-style hero carousel + horizontal rails.
 *
 * Phase 9: full carousel hero auto-cycling through up to 5 featured
 * items, upgraded rail typography, shimmer loaders while data loads.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    onNavigate: AppNavState? = null,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val sync by viewModel.syncProgress.collectAsState()
    val genreRails by viewModel.genreRails.collectAsState()
    val providerRails by viewModel.providerRails.collectAsState()
    val watchlist by viewModel.watchlist.collectAsState()
    val newEpisodeShows by viewModel.newEpisodeShows.collectAsState()

    // Build a Set of series titles that currently have new episodes —
    // used to overlay the "NEW" badge on any show poster across the
    // home screen (watchlist rail, new-episodes rail itself).  Series
    // are addressed by `seriesTitle`, which is also how watchlist
    // stores show entries (their contentId = seriesTitle), so a single
    // lookup table keeps the badging cheap.
    val newEpisodeTitles by remember(newEpisodeShows) {
        derivedStateOf {
            newEpisodeShows.mapTo(HashSet(newEpisodeShows.size)) { it.seriesTitle }
        }
    }

    // Compute the watchlist content-id set once per watchlist change and
    // reuse across all three rails.  Previously recomputed inline three
    // times per recomposition — during enrichment churn with 8+ rails
    // that was ~24 Set<String> allocations per frame (idioms review #5,
    // perf review #7).
    val watchlistIds by remember(watchlist) {
        derivedStateOf { watchlist.mapTo(HashSet(watchlist.size)) { it.contentId } }
    }

    val scrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()

    // -- Context menu state -----------------------------------------------
    var contextMenuVisible by remember { mutableStateOf(false) }
    var contextMenuActions by remember { mutableStateOf<List<ContextMenuAction>>(emptyList()) }

    // The hero carousel renders its own backdrop + gradients, so we
    // don't layer an ImmersiveBackdrop behind the whole screen — that
    // caused a double-image "cut off and repeat" artefact.
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(StrataColors.SurfaceVoid)
            .verticalScroll(scrollState),
    ) {
            // -- Hero carousel (uses backdrops when available) ----------
            HeroCarousel(
                heroes = state.heroCandidates,
                fallbackMovies = state.recentMovies.take(5),
                onMovieClick = { contentId ->
                    onNavigate?.openMovieDetail(contentId)
                },
            )

            SyncBanner(sync)

            Spacer(Modifier.height(24.dp))

            // -- Rails ---------------------------------------------------
            val isLoading = state.recentMovies.isEmpty() &&
                sync != SyncService.Progress.Idle &&
                sync !is SyncService.Progress.Done

            if (state.continueWatching.isNotEmpty()) {
                Rail(
                    title = "Continue Watching",
                    accentColor = StrataColors.AccentSecondary,
                    items = state.continueWatching,
                ) { _, item ->
                    Box(
                        modifier = Modifier.onPreviewKeyEvent { event ->
                            if (event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                                event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_MENU
                            ) {
                                contextMenuActions = listOf(
                                    ContextMenuAction("Remove from Continue Watching") {
                                        viewModel.removeContinueWatching(item.contentId)
                                    },
                                )
                                contextMenuVisible = true
                                true
                            } else false
                        },
                    ) {
                        CwCard(
                            item = item,
                            onFocused = {},
                            onClick = {
                                // Show CW entries open the series detail
                                // so the smart-resume logic there can
                                // decide whether to resume mid-episode
                                // or queue the next one.  Movies + live
                                // still go straight to the player.
                                if (item.contentType == "show") {
                                    onNavigate?.openShowDetail(
                                        item.seriesTitle ?: item.contentId,
                                    )
                                } else {
                                    onNavigate?.openPlayer(
                                        com.strata.tv.ui.nav.PlayerArgs(
                                            streamUrl = item.streamUrl,
                                            title = item.contentId,
                                            isLive = item.contentType == "live",
                                            resumePositionMs = item.resumePositionMs,
                                            contentType = item.contentType,
                                            artworkUrl = item.artworkUrl,
                                            contentId = item.contentId,
                                        ),
                                    )
                                }
                            },
                            badge = if (
                                item.contentType == "show" &&
                                item.seriesTitle != null &&
                                item.seriesTitle in newEpisodeTitles
                            ) "NEW" else null,
                        )
                    }
                }
            }

            // -- New Episodes rail --------------------------------------------
            // Sits between Continue Watching and the Watchlist so users
            // notice fresh content the moment they land on Home.  Only
            // shown when the user has at least one followed series with
            // recent episodes, otherwise we'd render an empty header.
            if (newEpisodeShows.isNotEmpty()) {
                Rail(
                    title = "New Episodes",
                    accentColor = StrataColors.AccentSecondary,
                    items = newEpisodeShows,
                ) { _, show ->
                    PosterCard(
                        title = show.seriesTitle,
                        subtitle = "${show.totalSeasons}S · ${show.totalEpisodes}E",
                        posterUrl = show.posterUrl.takeIf { it.isNotBlank() },
                        badge = "NEW",
                        onClick = { onNavigate?.openShowDetail(show.seriesTitle) },
                    )
                }
            }

            // -- Watchlist rail -----------------------------------------------
            if (watchlist.isNotEmpty()) {
                Rail(
                    title = "My Watchlist",
                    accentColor = StrataColors.AccentPrimary,
                    items = watchlist,
                ) { _, item ->
                    Box(
                        modifier = Modifier.onPreviewKeyEvent { event ->
                            if (event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                                event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_MENU
                            ) {
                                contextMenuActions = listOf(
                                    ContextMenuAction("Remove from Watchlist") {
                                        viewModel.removeFromWatchlist(item.contentId)
                                    },
                                )
                                contextMenuVisible = true
                                true
                            } else false
                        },
                    ) {
                        // Watchlist stores show entries with
                        // `contentId = seriesTitle`, so the same set
                        // that drives the New Episodes rail can decide
                        // whether to badge the row here too.
                        val showBadge = item.contentType == "show" &&
                            item.contentId in newEpisodeTitles
                        PosterCard(
                            title = item.title,
                            subtitle = null,
                            posterUrl = item.artworkUrl.takeIf { it.isNotBlank() },
                            badge = if (showBadge) "NEW" else null,
                            onClick = {
                                if (item.contentType == "show") {
                                    onNavigate?.openShowDetail(item.contentId)
                                } else {
                                    onNavigate?.openMovieDetail(item.contentId)
                                }
                            },
                        )
                    }
                }
            }

            if (state.recentMovies.isNotEmpty()) {
                Rail(
                    title = "Recently Added",
                    accentColor = StrataColors.AccentPrimary,
                    items = state.recentMovies,
                ) { _, item ->
                    MovieCardWithContextMenu(
                        movie = item,
                        watchlistIds = watchlistIds,
                        onMenuShow = { actions ->
                            contextMenuActions = actions
                            contextMenuVisible = true
                        },
                        viewModel = viewModel,
                        onClick = { onNavigate?.openMovieDetail(item.contentId) },
                    )
                }
            } else if (isLoading) {
                ShimmerRail()
                ShimmerRail()
            }

            // -- Provider rails ("New on Netflix", etc.) ------------------
            providerRails.forEach { rail ->
                Rail(
                    title = rail.title,
                    accentColor = StrataColors.AccentPrimary,
                    items = rail.movies,
                ) { _, movie ->
                    MovieCardWithContextMenu(
                        movie = movie,
                        watchlistIds = watchlistIds,
                        onMenuShow = { actions ->
                            contextMenuActions = actions
                            contextMenuVisible = true
                        },
                        viewModel = viewModel,
                        onClick = { onNavigate?.openMovieDetail(movie.contentId) },
                    )
                }
            }

            // -- Genre rails (populated as enrichment runs) --------------
            val accentColors = listOf(
                StrataColors.AccentSecondary,
                StrataColors.AccentPrimary,
                StrataColors.StatusLive,
                Color(0xFF4A9BD9), // blue accent
            )
            genreRails.forEachIndexed { index, rail ->
                Rail(
                    title = rail.genre,
                    accentColor = accentColors[index % accentColors.size],
                    items = rail.movies,
                ) { _, movie ->
                    MovieCardWithContextMenu(
                        movie = movie,
                        watchlistIds = watchlistIds,
                        onMenuShow = { actions ->
                            contextMenuActions = actions
                            contextMenuVisible = true
                        },
                        viewModel = viewModel,
                        onClick = { onNavigate?.openMovieDetail(movie.contentId) },
                    )
                }
            }

        Spacer(Modifier.height(48.dp))
    }

    // -- Context menu overlay (rendered outside the scroll column) ----
    CardContextMenu(
        visible = contextMenuVisible,
        actions = contextMenuActions,
        onDismiss = { contextMenuVisible = false },
    )
}

// =====================================================================
// Hero Carousel -- auto-cycling through featured movies
// =====================================================================

/**
 * Hero carousel — uses landscape TMDB backdrops when available (from
 * [heroes]), falling back to poster-based items from [fallbackMovies].
 * Shows title, year, genre, and overview when the full entity is available.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun HeroCarousel(
    heroes: List<MovieEntity>,
    fallbackMovies: List<MovieListItem>,
    onMovieClick: (String) -> Unit,
) {
    // Prefer heroes (have backdrops), fall back to recent posters.
    val useHeroes = heroes.isNotEmpty()
    val count = if (useHeroes) heroes.size else fallbackMovies.size

    if (count == 0) {
        StaticHero()
        return
    }

    var currentIndex by remember { mutableIntStateOf(0) }
    var isPaused by remember { mutableStateOf(false) }

    // Auto-cycle every 6 seconds, pause on focus.  Keyed on isPaused only
    // so populating hero candidates after enrichment doesn't spuriously
    // reset the timer (idioms review #9).  A while-loop drives the
    // advancement so we only launch one coroutine per (un)pause.
    LaunchedEffect(isPaused, count > 1) {
        while (!isPaused && count > 1) {
            delay(6_000)
            currentIndex = (currentIndex + 1) % count
        }
    }

    // Derive display data for the current index.
    val title: String
    val year: Int?
    val genre: String
    val overview: String
    val imageUrl: String?
    val contentId: String

    if (useHeroes) {
        val h = heroes[currentIndex]
        title = h.movieTitle
        year = h.year
        genre = h.genre
        overview = h.overview
        imageUrl = h.backdropUrl.takeIf { it.isNotBlank() } ?: h.posterUrl.takeIf { it.isNotBlank() }
        contentId = h.contentId
    } else {
        val m = fallbackMovies[currentIndex]
        title = m.movieTitle
        year = m.year
        genre = m.genre
        overview = ""
        imageUrl = m.posterUrl.takeIf { it.isNotBlank() }
        contentId = m.contentId
    }

    Box(
        // Align TopStart so the hero's focus bounds start at the left edge.
        // This causes Compose's focus search on D-pad Down to pick the
        // leftmost card in the first rail below, not the middle one.
        contentAlignment = Alignment.TopStart,
        modifier = Modifier
            .fillMaxWidth()
            .height(340.dp)
            .focusable()
            .onFocusChanged { isPaused = it.hasFocus }
            .onPreviewKeyEvent { event ->
                if (event.nativeKeyEvent.action != android.view.KeyEvent.ACTION_DOWN) {
                    return@onPreviewKeyEvent false
                }
                when (event.nativeKeyEvent.keyCode) {
                    android.view.KeyEvent.KEYCODE_DPAD_LEFT -> {
                        currentIndex = if (currentIndex > 0) currentIndex - 1 else count - 1
                        true
                    }
                    android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        currentIndex = (currentIndex + 1) % count
                        true
                    }
                    android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                    android.view.KeyEvent.KEYCODE_ENTER -> {
                        onMovieClick(contentId)
                        true
                    }
                    else -> false
                }
            },
    ) {
        // Backdrop image with crossfade (400 ms — halved from 800 ms to
        // reduce the time two full-width bitmaps are composited together
        // on a Fire Stick's modest GPU).
        AnimatedContent(
            targetState = currentIndex,
            transitionSpec = {
                fadeIn(tween(400)) togetherWith fadeOut(tween(400))
            },
            label = "hero-carousel",
        ) { index ->
            val url = if (useHeroes) {
                heroes.getOrNull(index)?.let {
                    it.backdropUrl.takeIf { u -> u.isNotBlank() } ?: it.posterUrl.takeIf { u -> u.isNotBlank() }
                }
            } else {
                fallbackMovies.getOrNull(index)?.posterUrl?.takeIf { it.isNotBlank() }
            }
            if (url != null) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(url)
                        // Pre-scale on the cache side — hero fills the screen
                        // but Coil can still decode to a budget rather than
                        // loading the full 1280-wide bitmap.
                        .size(1280, 720)
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.linearGradient(
                                colors = listOf(Color(0xFF1E1040), Color(0xFF150D30)),
                            ),
                        ),
                )
            }
        }

        // Gradient overlays
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.0f to Color.Black.copy(alpha = 0.1f),
                            0.4f to Color.Black.copy(alpha = 0.4f),
                            0.7f to Color.Black.copy(alpha = 0.7f),
                            1.0f to StrataColors.SurfaceVoid,
                        ),
                    ),
                ),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        colorStops = arrayOf(
                            0.0f to Color.Black.copy(alpha = 0.6f),
                            0.5f to Color.Black.copy(alpha = 0.1f),
                            1.0f to Color.Transparent,
                        ),
                    ),
                ),
        )

        // Hero text overlay
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 32.dp, bottom = 56.dp, end = 200.dp),
        ) {
            Text(
                text = title,
                color = Color.White,
                fontSize = 36.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 42.sp,
                letterSpacing = (-0.5).sp,
            )
            if (year != null || genre.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (year != null) {
                        Text(
                            text = year.toString(),
                            color = StrataColors.TextSecondary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    if (year != null && genre.isNotBlank()) {
                        Text(
                            text = "  \u00B7  ",
                            color = StrataColors.TextTertiary,
                            fontSize = 14.sp,
                        )
                    }
                    if (genre.isNotBlank()) {
                        Text(
                            text = genre.split(",", "|").take(2).joinToString(", ") { it.trim() },
                            color = StrataColors.TextSecondary,
                            fontSize = 14.sp,
                        )
                    }
                }
            }
            // Overview (only from full entity heroes)
            if (overview.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = overview,
                    color = StrataColors.TextSecondary,
                    fontSize = 13.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 18.sp,
                )
            }
        }

        // Carousel dots
        if (count > 1) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                repeat(count) { index ->
                    Box(
                        modifier = Modifier
                            .size(if (index == currentIndex) 8.dp else 6.dp)
                            .clip(CircleShape)
                            .background(
                                if (index == currentIndex) StrataColors.AccentPrimary
                                else StrataColors.TextTertiary.copy(alpha = 0.4f),
                            ),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun StaticHero() {
    // Shown briefly after the splash dismisses but before the first
    // sync completes.  Quiet, on-brand, no marketing copy.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp)
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF3A1B7A),
                        Color(0xFF1B0C3F),
                        StrataColors.SurfaceVoid,
                    ),
                    radius = 600f,
                ),
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 32.dp),
        ) {
            Text(
                text = "Setting things up…",
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.3).sp,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Your library will appear here once the first sync finishes.",
                color = StrataColors.TextSecondary,
                fontSize = 14.sp,
            )
        }
    }
}

// =====================================================================
// Helper cards
// =====================================================================

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SyncBanner(progress: SyncService.Progress) {
    val msg = when (progress) {
        SyncService.Progress.Idle, is SyncService.Progress.Done -> return
        is SyncService.Progress.Downloading -> "Downloading playlist\u2026"
        is SyncService.Progress.Parsing ->
            "Parsing playlist\u2026 ${progress.parsed} entries"
        SyncService.Progress.PostProcessing -> "Sorting + writing to library\u2026"
        is SyncService.Progress.Error -> "Sync failed: ${progress.message}"
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(StrataColors.SurfaceRaised)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(msg, color = StrataColors.TextSecondary, fontSize = 12.sp)
    }
}

/**
 * A MovieCard wrapped in a Box that intercepts KEYCODE_MENU to show a
 * context menu.  Always includes the Watchlist toggle; conditionally
 * adds Hide / Ignore Genre / Ignore Language entries (issue: cards
 * built from poor TMDB data should still be removable).
 */
@Composable
private fun MovieCardWithContextMenu(
    movie: MovieListItem,
    watchlistIds: Set<String>,
    onMenuShow: (List<ContextMenuAction>) -> Unit,
    viewModel: HomeViewModel,
    onClick: () -> Unit,
) {
    val inWatchlist = movie.contentId in watchlistIds

    Box(
        modifier = Modifier.onPreviewKeyEvent { event ->
            if (event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_MENU
            ) {
                onMenuShow(buildMovieMenu(movie, inWatchlist, viewModel))
                true
            } else false
        },
    ) {
        MovieCard(
            movie = movie,
            onFocused = {},
            onClick = onClick,
        )
    }
}

private fun buildMovieMenu(
    movie: MovieListItem,
    inWatchlist: Boolean,
    viewModel: HomeViewModel,
): List<ContextMenuAction> {
    val actions = mutableListOf<ContextMenuAction>()
    if (inWatchlist) {
        actions += ContextMenuAction("Remove from Watchlist") {
            viewModel.removeFromWatchlist(movie.contentId)
        }
    } else {
        actions += ContextMenuAction("Add to Watchlist") {
            viewModel.addToWatchlist(
                contentId = movie.contentId,
                title = movie.movieTitle,
                artworkUrl = movie.posterUrl,
            )
        }
    }
    actions += ContextMenuAction("Hide this film") {
        viewModel.hideMovie(movie.contentId)
    }
    // Genre column is a comma-separated TMDB string — give the user one
    // entry per token so multi-genre titles ("Action, Crime") let them
    // drop just one without nuking the other.
    movie.genre
        .split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .forEach { g ->
            actions += ContextMenuAction("Ignore genre: $g") {
                viewModel.ignoreGenre(g)
            }
        }
    if (movie.language.isNotBlank()) {
        val name = com.strata.tv.data.settings.AppSettings.languageDisplayName(movie.language)
        actions += ContextMenuAction("Ignore language: $name") {
            viewModel.ignoreLanguage(movie.language)
        }
    }
    return actions
}

@Composable
private fun MovieCard(
    movie: MovieListItem,
    onFocused: () -> Unit = {},
    onClick: () -> Unit = {},
) {
    val subtitle = buildString {
        movie.year?.let { append(it) }
        if (movie.rating > 0.0) {
            if (isNotEmpty()) append("  \u00B7  ")
            append("\u2605 ")
            append(String.format("%.1f", movie.rating))
        }
    }.ifEmpty { null }

    PosterCard(
        title = movie.movieTitle,
        subtitle = subtitle,
        posterUrl = movie.posterUrl.takeIf { it.isNotBlank() },
        onClick = onClick,
        onFocused = onFocused,
    )
}

@Composable
private fun CwCard(
    item: ContinueWatchingEntity,
    onFocused: () -> Unit = {},
    onClick: () -> Unit = {},
    badge: String? = null,
) {
    val pct = if (item.totalDurationMs > 0) {
        (item.resumePositionMs.toFloat() / item.totalDurationMs * 100).toInt()
    } else 0
    val isLive = item.contentType == "live"
    // Prefer the human-readable series title for show CW entries so the
    // card doesn't display the opaque content-id hash.  Movies still
    // get the contentId fallback \u2014 the watchlist already gives them a
    // proper title elsewhere on screen.
    val displayTitle = item.seriesTitle ?: item.contentId
    val subtitle = when {
        isLive -> "Resume"
        item.contentType == "show" && item.seasonNumber != null && item.episodeNumber != null ->
            "S${item.seasonNumber}E${item.episodeNumber} \u00B7 $pct%"
        else -> "Resume \u00B7 $pct%"
    }
    PosterCard(
        title = displayTitle,
        subtitle = subtitle,
        posterUrl = item.artworkUrl.takeIf { it.isNotBlank() },
        onClick = onClick,
        onFocused = onFocused,
        imageScale = if (isLive) ContentScale.Fit else ContentScale.Crop,
        cardSize = if (isLive) DpSize(160.dp, 120.dp) else DpSize(140.dp, 210.dp),
        badge = badge,
    )
}
