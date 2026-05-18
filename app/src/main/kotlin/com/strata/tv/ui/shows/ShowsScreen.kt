package com.strata.tv.ui.shows

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
import com.strata.tv.data.db.SeriesEntity
import com.strata.tv.ui.nav.AppNavState
import com.strata.tv.ui.theme.StrataColors
import com.strata.tv.ui.widgets.CardContextMenu
import com.strata.tv.ui.widgets.ContextMenuAction
import com.strata.tv.ui.widgets.PosterCard
import com.strata.tv.ui.widgets.Rail
import com.strata.tv.ui.widgets.ShimmerHero
import com.strata.tv.ui.widgets.ShimmerRail

/**
 * Box Sets (Shows) screen -- hero + genre rails.
 *
 * Phase 9: cinematic hero for the featured series,
 * genre-grouped rails, shimmer loading state.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ShowsScreen(
    modifier: Modifier = Modifier,
    onNavigate: AppNavState? = null,
    viewModel: ShowsViewModel = hiltViewModel(),
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
                ShowHero(show = hero)
            } else if (state.allShows.isEmpty()) {
                ShimmerHero()
            }

            Spacer(Modifier.height(8.dp))

            // -- Genre rails -------------------------------------------------
            if (state.genreRails.isNotEmpty()) {
                val colors = listOf(
                    StrataColors.AccentSecondary,
                    StrataColors.AccentPrimary,
                    StrataColors.StatusLive,
                    StrataColors.AccentPrimaryBright,
                )
                state.genreRails.forEachIndexed { index, rail ->
                    Rail(
                        title = rail.genre,
                        accentColor = colors[index % colors.size],
                        items = rail.shows,
                    ) { _, show ->
                        ShowPosterWithMenu(
                            show = show,
                            watchlistIds = watchlistIds,
                            viewModel = viewModel,
                            onClick = { onNavigate?.openShowDetail(show.seriesTitle) },
                            onMenuShow = { actions ->
                                contextMenuActions = actions
                                contextMenuVisible = true
                            },
                        )
                    }
                }
            } else if (state.allShows.isEmpty()) {
                ShimmerRail()
                ShimmerRail()
            }

            // -- All Shows rail ----------------------------------------------
            if (state.allShows.isNotEmpty()) {
                Rail(
                    title = "All Box Sets",
                    accentColor = StrataColors.TextTertiary,
                    items = state.allShows,
                ) { _, show ->
                    ShowPosterWithMenu(
                        show = show,
                        watchlistIds = watchlistIds,
                        viewModel = viewModel,
                        onClick = { onNavigate?.openShowDetail(show.seriesTitle) },
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
private fun ShowPosterWithMenu(
    show: SeriesEntity,
    watchlistIds: Set<String>,
    viewModel: ShowsViewModel,
    onClick: () -> Unit,
    onMenuShow: (List<ContextMenuAction>) -> Unit,
) {
    val inWatchlist = show.seriesTitle in watchlistIds
    Box(
        modifier = Modifier.onPreviewKeyEvent { event ->
            if (event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_MENU
            ) {
                val actions = mutableListOf<ContextMenuAction>()
                if (inWatchlist) {
                    actions += ContextMenuAction("Remove from Watchlist") {
                        viewModel.removeFromWatchlist(show.seriesTitle)
                    }
                } else {
                    actions += ContextMenuAction("Add to Watchlist") {
                        viewModel.addToWatchlist(
                            seriesTitle = show.seriesTitle,
                            artworkUrl = show.posterUrl,
                        )
                    }
                }
                actions += ContextMenuAction("Hide this show") {
                    viewModel.hideSeries(show.seriesTitle)
                }
                show.genre
                    .split(',')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .distinct()
                    .forEach { g ->
                        actions += ContextMenuAction("Ignore genre: $g") {
                            viewModel.ignoreGenre(g)
                        }
                    }
                if (show.language.isNotBlank()) {
                    val name = com.strata.tv.data.settings.AppSettings
                        .languageDisplayName(show.language)
                    actions += ContextMenuAction("Ignore language: $name") {
                        viewModel.ignoreLanguage(show.language)
                    }
                }
                onMenuShow(actions)
                true
            } else false
        },
    ) {
        PosterCard(
            title = show.seriesTitle,
            subtitle = "${show.totalSeasons}S \u00B7 ${show.totalEpisodes}E",
            posterUrl = show.posterUrl.takeIf { it.isNotBlank() },
            badge = if (show.hasNewEpisodes) "NEW" else null,
            onClick = onClick,
        )
    }
}

// =====================================================================
// Show hero -- full-bleed backdrop for the featured series
// =====================================================================

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ShowHero(show: SeriesEntity) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(300.dp),
    ) {
        val backdropUrl = show.backdropUrl.takeIf { it.isNotBlank() }
            ?: show.posterUrl.takeIf { it.isNotBlank() }
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
                            colors = listOf(Color(0xFF0D2A3F), StrataColors.SurfaceVoid),
                        ),
                    ),
            )
        }

        // Gradient overlays
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
                text = "BOX SETS",
                color = StrataColors.AccentSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = show.seriesTitle,
                color = Color.White,
                fontSize = 36.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 42.sp,
                letterSpacing = (-0.5).sp,
            )
            if (show.totalSeasons > 0 || show.genre.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (show.totalSeasons > 0) {
                        Text(
                            text = "${show.totalSeasons} Season${if (show.totalSeasons > 1) "s" else ""}",
                            color = StrataColors.TextSecondary,
                            fontSize = 14.sp,
                        )
                    }
                    if (show.totalSeasons > 0 && show.genre.isNotBlank()) {
                        Spacer(Modifier.width(8.dp))
                        Text("\u00B7", color = StrataColors.TextTertiary, fontSize = 14.sp)
                        Spacer(Modifier.width(8.dp))
                    }
                    if (show.genre.isNotBlank()) {
                        Text(
                            text = show.genre.split(",", "|").take(3).joinToString(", ") { it.trim() },
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
