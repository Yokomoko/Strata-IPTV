package com.strata.tv.ui.home

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.strata.tv.data.db.ContinueWatchingEntity
import com.strata.tv.data.db.MovieEntity
import com.strata.tv.data.repo.SyncService
import com.strata.tv.ui.theme.StrataColors
import com.strata.tv.ui.widgets.Featured
import com.strata.tv.ui.widgets.ImmersiveBackdrop
import com.strata.tv.ui.widgets.PosterCard
import com.strata.tv.ui.widgets.Rail
import com.strata.tv.ui.widgets.rememberFeaturedState

/**
 * Home screen — Netflix-style vertical stack of horizontal rails.
 *
 * Connected to the real [HomeViewModel] in this phase, so the rails
 * reflect what's in Room.  On first launch the ViewModel kicks off a
 * one-shot M3U sync; until that completes the rails will be empty
 * and the [SyncBanner] shows progress at the top.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val sync by viewModel.syncProgress.collectAsState()
    val featured = rememberFeaturedState()

    // The screen is a Box stack: backdrop at the back, scrolling
    // content overlaid on top.  As cards gain focus they push the
    // featured-item state, which the backdrop animates to.
    Box(modifier = modifier.fillMaxSize()) {
        ImmersiveBackdrop(state = featured)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            Hero(
                channels = state.channelCount,
                movies = state.movieCount,
                series = state.seriesCount,
            )
            SyncBanner(sync)

            Spacer(Modifier.height(8.dp))

            if (state.continueWatching.isNotEmpty()) {
                Rail(
                    title = "Continue Watching",
                    accentColor = StrataColors.AccentSecondary,
                    items = state.continueWatching,
                ) { _, item ->
                    CwCard(item, onFocused = { featured.setFeatured(item.toFeatured()) })
                }
            }

            if (state.recentMovies.isNotEmpty()) {
                Rail(
                    title = "Recently Added",
                    accentColor = StrataColors.AccentPrimary,
                    items = state.recentMovies,
                ) { _, item ->
                    MovieCard(item, onFocused = { featured.setFeatured(item.toFeatured()) })
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun MovieEntity.toFeatured(): Featured = Featured(
    key = "movie:$contentId",
    title = movieTitle,
    backdropUrl = posterUrl.takeIf { it.isNotBlank() },
)

private fun ContinueWatchingEntity.toFeatured(): Featured = Featured(
    key = "cw:$contentId",
    title = contentId,
    backdropUrl = artworkUrl.takeIf { it.isNotBlank() },
)

@Composable
private fun Hero(channels: Int, movies: Int, series: Int) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(160.dp)
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        Color(0xFF1E1040),
                        Color(0xFF150D30),
                        StrataColors.SurfaceVoid,
                    ),
                ),
            ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 32.dp, vertical = 24.dp),
        ) {
            Text(
                text = "Strata",
                color = StrataColors.AccentPrimary,
                fontSize = 36.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Stat("$channels", "Channels", StrataColors.StatusLive)
                Stat("$movies", "Movies", StrataColors.AccentPrimary)
                Stat("$series", "Box Sets", StrataColors.AccentSecondary)
            }
        }
    }
}

@Composable
private fun Stat(value: String, label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text = value, color = color, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(4.dp))
        Text(text = label, color = StrataColors.TextTertiary, fontSize = 11.sp)
    }
}

@Composable
private fun SyncBanner(progress: SyncService.Progress) {
    val msg = when (progress) {
        SyncService.Progress.Idle, is SyncService.Progress.Done -> return
        is SyncService.Progress.Downloading -> "Downloading playlist…"
        is SyncService.Progress.Parsing ->
            "Parsing playlist… ${progress.parsed} entries"
        SyncService.Progress.PostProcessing -> "Sorting + writing to library…"
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
private fun MovieCard(movie: MovieEntity, onFocused: () -> Unit = {}) {
    PosterCard(
        title = movie.movieTitle,
        subtitle = movie.year?.toString(),
        posterUrl = movie.posterUrl.takeIf { it.isNotBlank() },
        onClick = { /* TODO Phase 4 player */ },
        cardSize = androidx.compose.ui.unit.DpSize(width = 140.dp, height = 252.dp),
        onFocused = onFocused,
    )
}

@Composable
private fun CwCard(item: ContinueWatchingEntity, onFocused: () -> Unit = {}) {
    val pct = if (item.totalDurationMs > 0) {
        (item.resumePositionMs.toFloat() / item.totalDurationMs * 100).toInt()
    } else 0
    PosterCard(
        title = item.contentId,
        subtitle = "Resume · $pct%",
        posterUrl = item.artworkUrl.takeIf { it.isNotBlank() },
        onClick = { /* TODO Phase 4 player */ },
        cardSize = androidx.compose.ui.unit.DpSize(width = 140.dp, height = 252.dp),
        onFocused = onFocused,
    )
}

private fun initialsFor(title: String): String {
    val words = title.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return "?"
    if (words.size == 1) return words[0].first().uppercaseChar().toString()
    return "${words[0].first()}${words[1].first()}".uppercase()
}
