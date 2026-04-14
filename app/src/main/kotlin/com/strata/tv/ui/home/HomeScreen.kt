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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
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
import coil3.compose.AsyncImage
import com.strata.tv.data.db.ContinueWatchingEntity
import com.strata.tv.data.db.MovieEntity
import com.strata.tv.data.db.MovieListItem
import com.strata.tv.data.repo.SyncService
import com.strata.tv.ui.nav.AppNavState
import com.strata.tv.ui.theme.StrataColors
import com.strata.tv.ui.widgets.Featured
import com.strata.tv.ui.widgets.ImmersiveBackdrop
import com.strata.tv.ui.widgets.PosterCard
import com.strata.tv.ui.widgets.Rail
import com.strata.tv.ui.widgets.ShimmerRail
import com.strata.tv.ui.widgets.rememberFeaturedState
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

    val scrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()

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

            // Invisible focusable anchor: when D-pad Up lands here from
            // the first rail, scroll the column back to reveal the hero.
            Spacer(
                Modifier
                    .height(1.dp)
                    .onFocusChanged { focusState ->
                        if (focusState.isFocused) {
                            coroutineScope.launch {
                                scrollState.animateScrollTo(0)
                            }
                        }
                    }
                    .focusable()
            )

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
                    CwCard(
                        item = item,
                        onFocused = {},
                        onClick = {
                            onNavigate?.openPlayer(
                                com.strata.tv.ui.nav.PlayerArgs(
                                    streamUrl = item.streamUrl,
                                    title = item.contentId,
                                    isLive = item.contentType == "live",
                                    resumePositionMs = item.resumePositionMs,
                                    contentType = item.contentType,
                                    artworkUrl = item.artworkUrl,
                                ),
                            )
                        },
                    )
                }
            }

            if (state.recentMovies.isNotEmpty()) {
                Rail(
                    title = "Recently Added",
                    accentColor = StrataColors.AccentPrimary,
                    items = state.recentMovies,
                ) { _, item ->
                    MovieCard(
                        movie = item,
                        onFocused = {},
                        onClick = {
                            onNavigate?.openMovieDetail(item.contentId)
                        },
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
                    MovieCard(
                        movie = movie,
                        onFocused = {},
                        onClick = {
                            onNavigate?.openMovieDetail(movie.contentId)
                        },
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
                    MovieCard(
                        movie = movie,
                        onFocused = {},
                        onClick = {
                            onNavigate?.openMovieDetail(movie.contentId)
                        },
                    )
                }
            }

        Spacer(Modifier.height(48.dp))
    }
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

    // Auto-cycle every 6 seconds, pause on focus
    LaunchedEffect(currentIndex, isPaused, count) {
        if (!isPaused && count > 1) {
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
        modifier = Modifier
            .fillMaxWidth()
            .height(340.dp)
            .onFocusChanged { isPaused = it.hasFocus },
    ) {
        // Backdrop image with crossfade
        AnimatedContent(
            targetState = currentIndex,
            transitionSpec = {
                fadeIn(tween(800)) togetherWith fadeOut(tween(800))
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
                    model = url,
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
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp)
            .background(
                Brush.linearGradient(
                    colors = listOf(Color(0xFF1E1040), Color(0xFF150D30), StrataColors.SurfaceVoid),
                ),
            ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 32.dp, vertical = 32.dp),
        ) {
            Text(
                text = "Strata",
                color = StrataColors.AccentPrimary,
                fontSize = 44.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-1).sp,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Your premium streaming library",
                color = StrataColors.TextSecondary,
                fontSize = 16.sp,
            )
        }
    }
}

// =====================================================================
// Helper conversions + cards
// =====================================================================

private fun MovieListItem.toFeatured(): Featured = Featured(
    key = "movie:$contentId",
    title = movieTitle,
    backdropUrl = posterUrl.takeIf { it.isNotBlank() },
    subtitle = listOfNotNull(
        year?.toString(),
        genre.takeIf { it.isNotBlank() }?.split(",", "|")?.firstOrNull()?.trim(),
    ).joinToString(" \u00B7 ").takeIf { it.isNotBlank() },
)

private fun ContinueWatchingEntity.toFeatured(): Featured = Featured(
    key = "cw:$contentId",
    title = contentId,
    backdropUrl = artworkUrl.takeIf { it.isNotBlank() },
)

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
) {
    val pct = if (item.totalDurationMs > 0) {
        (item.resumePositionMs.toFloat() / item.totalDurationMs * 100).toInt()
    } else 0
    val isLive = item.contentType == "live"
    PosterCard(
        title = item.contentId,
        subtitle = if (isLive) "Resume" else "Resume \u00B7 $pct%",
        posterUrl = item.artworkUrl.takeIf { it.isNotBlank() },
        onClick = onClick,
        onFocused = onFocused,
        imageScale = if (isLive) ContentScale.Fit else ContentScale.Crop,
        cardSize = if (isLive) DpSize(160.dp, 120.dp) else DpSize(140.dp, 210.dp),
    )
}
