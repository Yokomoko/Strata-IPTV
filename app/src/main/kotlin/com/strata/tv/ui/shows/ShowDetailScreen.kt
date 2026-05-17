package com.strata.tv.ui.shows

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.foundation.lazy.list.TvLazyRow
import androidx.tv.foundation.lazy.list.items
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.strata.tv.data.db.EpisodeEntity
import com.strata.tv.data.db.SeriesEntity
import com.strata.tv.ui.nav.PlayerArgs
import com.strata.tv.ui.theme.StrataColors
import com.strata.tv.ui.widgets.GenreChips
import com.strata.tv.ui.widgets.MetadataChip
import com.strata.tv.ui.widgets.ShimmerHero
import kotlinx.coroutines.launch

/**
 * Show detail screen -- full-bleed backdrop, poster, metadata,
 * season/episode selector, Play CTA.
 *
 * Follows the Netflix series detail pattern: big hero, season picker
 * as horizontal chips, episode list below.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ShowDetailScreen(
    seriesTitle: String,
    onBack: () -> Unit,
    onPlay: (PlayerArgs) -> Unit,
    viewModel: ShowDetailViewModel = hiltViewModel(),
) {
    LaunchedEffect(seriesTitle) { viewModel.load(seriesTitle) }
    val state by viewModel.state.collectAsState()
    val selectedSeason by viewModel.selectedSeason.collectAsState()

    BackHandler { onBack() }

    when (val s = state) {
        ShowDetailState.Loading -> {
            Box(Modifier.fillMaxSize().background(StrataColors.SurfaceVoid)) {
                ShimmerHero(height = 500)
            }
        }
        ShowDetailState.NotFound -> {
            Box(
                Modifier.fillMaxSize().background(StrataColors.SurfaceVoid),
                contentAlignment = Alignment.Center,
            ) {
                Text("Series not found", color = StrataColors.TextSecondary, fontSize = 16.sp)
            }
        }
        is ShowDetailState.Loaded -> {
            ShowDetailContent(
                series = s.series,
                seasons = s.seasons,
                isOnWatchlist = s.isOnWatchlist,
                resumeInfo = s.resumeInfo,
                selectedSeason = selectedSeason,
                onSelectSeason = { viewModel.selectSeason(it) },
                onBack = onBack,
                onPlay = onPlay,
                onToggleWatchlist = { viewModel.toggleWatchlist() },
                resolveStreamUrl = { episode -> viewModel.resolveStreamUrl(episode) },
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ShowDetailContent(
    series: SeriesEntity,
    seasons: Map<Int, List<EpisodeEntity>>,
    isOnWatchlist: Boolean,
    resumeInfo: ResumeInfo?,
    selectedSeason: Int,
    onSelectSeason: (Int) -> Unit,
    onBack: () -> Unit,
    onPlay: (PlayerArgs) -> Unit,
    onToggleWatchlist: () -> Unit,
    resolveStreamUrl: suspend (EpisodeEntity) -> String,
) {
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()

    // Ensure selectedSeason is valid
    val activeSeason = if (seasons.containsKey(selectedSeason)) selectedSeason
    else seasons.keys.firstOrNull() ?: 1
    val episodes = seasons[activeSeason] ?: emptyList()

    Box(modifier = Modifier.fillMaxSize()) {
        // -- Full-bleed backdrop -----------------------------------------
        val backdropUrl = series.backdropUrl.takeIf { it.isNotBlank() }
            ?: series.posterUrl.takeIf { it.isNotBlank() }
        if (backdropUrl != null) {
            AsyncImage(
                model = backdropUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(420.dp),
            )
        }

        // Gradients
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(420.dp)
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.0f to Color.Black.copy(alpha = 0.15f),
                            0.3f to Color.Black.copy(alpha = 0.4f),
                            0.6f to Color.Black.copy(alpha = 0.7f),
                            1.0f to StrataColors.SurfaceVoid,
                        ),
                    ),
                ),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(420.dp)
                .background(
                    Brush.horizontalGradient(
                        colorStops = arrayOf(
                            0.0f to Color.Black.copy(alpha = 0.65f),
                            0.5f to Color.Black.copy(alpha = 0.2f),
                            1.0f to Color.Transparent,
                        ),
                    ),
                ),
        )

        // -- Scrollable content ------------------------------------------
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(start = 32.dp, end = 32.dp, top = 60.dp, bottom = 48.dp),
        ) {
            // Invisible anchor: D-pad Up from top content scrolls back to hero.
            Spacer(
                Modifier
                    .height(1.dp)
                    .onFocusChanged { if (it.isFocused) scope.launch { scrollState.animateScrollTo(0) } }
                    .focusable()
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
            ) {
                // Poster
                val posterUrl = series.posterUrl.takeIf { it.isNotBlank() }
                if (posterUrl != null) {
                    AsyncImage(
                        model = posterUrl,
                        contentDescription = series.seriesTitle,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .width(160.dp)
                            .height(240.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .border(2.dp, StrataColors.SurfaceFloat, RoundedCornerShape(12.dp)),
                    )
                    Spacer(Modifier.width(24.dp))
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = series.seriesTitle,
                        color = Color.White,
                        fontSize = 36.sp,
                        fontWeight = FontWeight.Bold,
                        lineHeight = 42.sp,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        letterSpacing = (-0.5).sp,
                    )

                    Spacer(Modifier.height(10.dp))

                    // Metadata row
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (series.totalSeasons > 0) {
                            MetadataChip("${series.totalSeasons} Season${if (series.totalSeasons > 1) "s" else ""}")
                        }
                        if (series.totalEpisodes > 0) {
                            MetadataChip("${series.totalEpisodes} Episodes")
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    GenreChips(genres = series.genre)

                    Spacer(Modifier.height(16.dp))

                    // Plot / overview
                    if (series.plot.isNotBlank()) {
                        Text(
                            text = series.plot,
                            color = StrataColors.TextSecondary,
                            fontSize = 14.sp,
                            lineHeight = 22.sp,
                            maxLines = 5,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(16.dp))
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // -- Action buttons ------------------------------------------
            // Determine which episode the primary button targets:
            // 1. Resume mid-episode if the user has unfinished progress
            // 2. Play next episode if the last one was finished
            // 3. Fall back to first episode of the selected season
            val targetEp = resumeInfo?.episode ?: episodes.firstOrNull()
            val isResume = resumeInfo != null && resumeInfo.resumePositionMs > 0
            val buttonLabel = when {
                isResume -> "\u25B6  Continue S${targetEp!!.seasonNumber}E${targetEp.episodeNumber}"
                resumeInfo != null -> "\u25B6  Play S${targetEp!!.seasonNumber}E${targetEp.episodeNumber}"
                else -> "\u25B6  Play S${activeSeason}E1"
            }

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Surface(
                    onClick = {
                        if (targetEp != null) {
                            scope.launch {
                                val url = resolveStreamUrl(targetEp)
                                if (url.isNotBlank()) {
                                    onPlay(
                                        PlayerArgs(
                                            streamUrl = url,
                                            title = "${series.seriesTitle} S${targetEp.seasonNumber}E${targetEp.episodeNumber}",
                                            isLive = false,
                                            resumePositionMs = resumeInfo?.resumePositionMs ?: targetEp.resumePositionMs,
                                            contentType = "show",
                                            artworkUrl = series.posterUrl,
                                            seriesTitle = series.seriesTitle,
                                            seasonNumber = targetEp.seasonNumber,
                                            episodeNumber = targetEp.episodeNumber,
                                        ),
                                    )
                                }
                            }
                        }
                    },
                    shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(8.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = StrataColors.AccentPrimary,
                        focusedContainerColor = StrataColors.AccentPrimaryBright,
                    ),
                    modifier = Modifier.height(48.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 28.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = buttonLabel,
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }

                // Watchlist toggle
                Surface(
                    onClick = onToggleWatchlist,
                    shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(8.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = StrataColors.SurfaceFloat,
                        focusedContainerColor = StrataColors.SurfaceOverlay,
                    ),
                    modifier = Modifier.height(48.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (isOnWatchlist) "\u2713  On Watchlist" else "+  Watchlist",
                            color = StrataColors.TextPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            // -- Season selector -----------------------------------------
            if (seasons.size > 1) {
                Text(
                    text = "SEASONS",
                    color = StrataColors.TextTertiary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp,
                )
                Spacer(Modifier.height(8.dp))
                TvLazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(end = 32.dp),
                ) {
                    items(
                        items = seasons.keys.toList(),
                        key = { it },
                    ) { season ->
                        val isSelected = season == activeSeason
                        var chipFocused by remember { mutableStateOf(false) }
                        Surface(
                            onClick = { onSelectSeason(season) },
                            shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(8.dp)),
                            colors = ClickableSurfaceDefaults.colors(
                                containerColor = if (isSelected) StrataColors.AccentPrimary else StrataColors.SurfaceRaised,
                                focusedContainerColor = StrataColors.AccentPrimary,
                            ),
                            modifier = Modifier
                                .height(36.dp)
                                .onFocusChanged { chipFocused = it.isFocused },
                        ) {
                            Text(
                                text = "Season $season",
                                color = if (isSelected || chipFocused) Color.White else StrataColors.TextSecondary,
                                fontSize = 13.sp,
                                fontWeight = if (isSelected || chipFocused) FontWeight.SemiBold else FontWeight.Normal,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            // -- Episode list --------------------------------------------
            Text(
                text = "Season $activeSeason",
                color = StrataColors.TextPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.3).sp,
            )
            Spacer(Modifier.height(12.dp))

            episodes.forEach { episode ->
                EpisodeRow(
                    episode = episode,
                    seriesTitle = series.seriesTitle,
                    posterUrl = series.posterUrl,
                    onPlay = onPlay,
                    resolveStreamUrl = resolveStreamUrl,
                )
                Spacer(Modifier.height(4.dp))
            }

            if (episodes.isEmpty()) {
                Text(
                    text = "No episodes found for this season.",
                    color = StrataColors.TextTertiary,
                    fontSize = 14.sp,
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun EpisodeRow(
    episode: EpisodeEntity,
    seriesTitle: String,
    posterUrl: String,
    onPlay: (PlayerArgs) -> Unit,
    resolveStreamUrl: suspend (EpisodeEntity) -> String,
) {
    val scope = rememberCoroutineScope()

    Surface(
        onClick = {
            scope.launch {
                val url = resolveStreamUrl(episode)
                if (url.isNotBlank()) {
                    onPlay(
                        PlayerArgs(
                            streamUrl = url,
                            title = "$seriesTitle S${episode.seasonNumber}E${episode.episodeNumber}",
                            isLive = false,
                            resumePositionMs = episode.resumePositionMs,
                            contentType = "show",
                            artworkUrl = posterUrl,
                            seriesTitle = seriesTitle,
                            seasonNumber = episode.seasonNumber,
                            episodeNumber = episode.episodeNumber,
                        ),
                    )
                }
            }
        },
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = StrataColors.SurfaceBase,
            focusedContainerColor = StrataColors.SurfaceRaised,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Episode number
            Text(
                text = "E${episode.episodeNumber}",
                color = StrataColors.AccentPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.width(40.dp),
            )

            Spacer(Modifier.width(12.dp))

            // Episode title or fallback
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = episode.episodeTitle.ifBlank { "Episode ${episode.episodeNumber}" },
                    color = StrataColors.TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (episode.runtime != null) {
                    Text(
                        text = "${episode.runtime}m",
                        color = StrataColors.TextTertiary,
                        fontSize = 11.sp,
                    )
                }
            }

            // Watch progress indicator
            if (episode.resumePositionMs > 0) {
                Text(
                    text = if (episode.watched) "\u2713 Watched" else "Resume",
                    color = if (episode.watched) StrataColors.StatusSuccess else StrataColors.AccentSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}
