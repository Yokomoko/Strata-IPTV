package com.strata.tv.ui.movies

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import android.view.KeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.strata.tv.data.db.MovieListItem
import com.strata.tv.ui.nav.AppNavState
import com.strata.tv.ui.theme.StrataColors
import com.strata.tv.ui.widgets.CardContextMenu
import com.strata.tv.ui.widgets.ContextMenuAction
import com.strata.tv.ui.widgets.PosterCard
import com.strata.tv.ui.widgets.Rail
import com.strata.tv.ui.widgets.ShimmerHero
import com.strata.tv.ui.widgets.ShimmerRail

/**
 * Movies screen -- hero section + genre-grouped rails.
 *
 * Phase 9: full cinematic hero for the featured movie,
 * genre rails with immersive list feel, shimmer loaders.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun MoviesScreen(
    modifier: Modifier = Modifier,
    onNavigate: AppNavState? = null,
    viewModel: MoviesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val watchlistIds by viewModel.watchlistIds.collectAsState()
    var contextMenuVisible by remember { mutableStateOf(false) }
    var contextMenuActions by remember { mutableStateOf<List<ContextMenuAction>>(emptyList()) }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            // -- Hero --------------------------------------------------------
            val hero = state.hero
            if (hero != null) {
                MovieHero(
                    movie = hero,
                    onClick = { onNavigate?.openMovieDetail(hero.contentId) },
                )
            } else if (state.allMovies.isEmpty()) {
                ShimmerHero()
            }

            Spacer(Modifier.height(8.dp))

            // -- Genre rails -------------------------------------------------
            if (state.genreRails.isNotEmpty()) {
                val colors = listOf(
                    StrataColors.AccentPrimary,
                    StrataColors.AccentSecondary,
                    StrataColors.StatusLive,
                    StrataColors.AccentPrimaryBright,
                )
                state.genreRails.forEachIndexed { index, rail ->
                    Rail(
                        title = rail.genre,
                        accentColor = colors[index % colors.size],
                        items = rail.movies,
                    ) { _, movie ->
                        MoviePosterWithMenu(
                            movie = movie,
                            watchlistIds = watchlistIds,
                            viewModel = viewModel,
                            onClick = { onNavigate?.openMovieDetail(movie.contentId) },
                            onMenuShow = { actions ->
                                contextMenuActions = actions
                                contextMenuVisible = true
                            },
                        )
                    }
                }
            } else if (state.allMovies.isEmpty()) {
                ShimmerRail()
                ShimmerRail()
            }

            // -- "All Movies" rail -------------------------------------------
            if (state.allMovies.isNotEmpty()) {
                Rail(
                    title = "All Movies",
                    accentColor = StrataColors.TextTertiary,
                    items = state.allMovies,
                ) { _, movie ->
                    MoviePosterWithMenu(
                        movie = movie,
                        watchlistIds = watchlistIds,
                        viewModel = viewModel,
                        onClick = { onNavigate?.openMovieDetail(movie.contentId) },
                        onMenuShow = { actions ->
                            contextMenuActions = actions
                            contextMenuVisible = true
                        },
                    )
                }
            }

            Spacer(Modifier.height(48.dp))
        }

        CardContextMenu(
            visible = contextMenuVisible,
            actions = contextMenuActions,
            onDismiss = { contextMenuVisible = false },
        )
    }
}

// =====================================================================
// PosterCard wrapped with KEYCODE_MENU interception for watchlist
// =====================================================================

@Composable
private fun MoviePosterWithMenu(
    movie: MovieListItem,
    watchlistIds: Set<String>,
    viewModel: MoviesViewModel,
    onClick: () -> Unit,
    onMenuShow: (List<ContextMenuAction>) -> Unit,
) {
    val inWatchlist = movie.contentId in watchlistIds
    Box(
        modifier = Modifier.onPreviewKeyEvent { event ->
            if (event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_MENU
            ) {
                val actions = if (inWatchlist) {
                    listOf(
                        ContextMenuAction("Remove from Watchlist") {
                            viewModel.removeFromWatchlist(movie.contentId)
                        },
                    )
                } else {
                    listOf(
                        ContextMenuAction("Add to Watchlist") {
                            viewModel.addToWatchlist(
                                contentId = movie.contentId,
                                title = movie.movieTitle,
                                artworkUrl = movie.posterUrl,
                            )
                        },
                    )
                }
                onMenuShow(actions)
                true
            } else false
        },
    ) {
        PosterCard(
            title = movie.movieTitle,
            subtitle = movie.year?.toString(),
            posterUrl = movie.posterUrl.takeIf { it.isNotBlank() },
            onClick = onClick,
        )
    }
}

// =====================================================================
// Movie hero -- full-bleed backdrop for the featured movie
// =====================================================================

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun MovieHero(
    movie: MovieListItem,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(300.dp),
    ) {
        // Backdrop
        val backdropUrl = movie.posterUrl.takeIf { it.isNotBlank() }
        if (backdropUrl != null) {
            AsyncImage(
                model = backdropUrl,
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
                            colors = listOf(Color(0xFF1E1040), StrataColors.SurfaceVoid),
                        ),
                    ),
            )
        }

        // Gradient overlay
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.0f to Color.Black.copy(alpha = 0.15f),
                            0.5f to Color.Black.copy(alpha = 0.5f),
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
                            0.5f to Color.Black.copy(alpha = 0.15f),
                            1.0f to Color.Transparent,
                        ),
                    ),
                ),
        )

        // Text overlay
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 32.dp, bottom = 24.dp, end = 200.dp),
        ) {
            Text(
                text = "MOVIES",
                color = StrataColors.AccentPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = movie.movieTitle,
                color = Color.White,
                fontSize = 36.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 42.sp,
                letterSpacing = (-0.5).sp,
            )
            if (movie.year != null || movie.genre.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (movie.year != null) {
                        Text(
                            text = movie.year.toString(),
                            color = StrataColors.TextSecondary,
                            fontSize = 14.sp,
                        )
                    }
                    if (movie.year != null && movie.genre.isNotBlank()) {
                        Spacer(Modifier.width(8.dp))
                        Text("\u00B7", color = StrataColors.TextTertiary, fontSize = 14.sp)
                        Spacer(Modifier.width(8.dp))
                    }
                    if (movie.genre.isNotBlank()) {
                        Text(
                            text = movie.genre.split(",", "|").take(3).joinToString(", ") { it.trim() },
                            color = StrataColors.TextSecondary,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
