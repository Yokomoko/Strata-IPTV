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
import com.strata.tv.ui.widgets.Rail

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

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(StrataColors.SurfaceVoid)
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
            ) { _, item -> CwCard(item) }
        }

        if (state.recentMovies.isNotEmpty()) {
            Rail(
                title = "Recently Added",
                accentColor = StrataColors.AccentPrimary,
                items = state.recentMovies,
            ) { _, item -> MovieCard(item) }
        }

        Spacer(Modifier.height(24.dp))
    }
}

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

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun MovieCard(movie: MovieEntity) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = { /* TODO Phase 4 player */ },
        modifier = Modifier
            .size(width = 120.dp, height = 220.dp)
            .onFocusChanged { focused = it.isFocused },
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = StrataColors.SurfaceRaised,
            focusedContainerColor = StrataColors.AccentPrimary,
        ),
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
                    .background(StrataColors.SurfaceFloat),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = initialsFor(movie.movieTitle),
                    color = StrataColors.TextSecondary,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = movie.movieTitle,
                color = if (focused) Color.White else StrataColors.TextPrimary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
            )
            movie.year?.let {
                Text(
                    text = it.toString(),
                    color = StrataColors.TextTertiary,
                    fontSize = 10.sp,
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CwCard(item: ContinueWatchingEntity) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = { /* TODO Phase 4 player */ },
        modifier = Modifier
            .size(width = 120.dp, height = 220.dp)
            .onFocusChanged { focused = it.isFocused },
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = StrataColors.SurfaceRaised,
            focusedContainerColor = StrataColors.AccentPrimary,
        ),
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text(
                text = item.contentId,
                color = if (focused) Color.White else StrataColors.TextPrimary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
            )
            val pct = if (item.totalDurationMs > 0) {
                (item.resumePositionMs.toFloat() / item.totalDurationMs * 100).toInt()
            } else 0
            Text(
                text = "Resume · $pct%",
                color = StrataColors.TextTertiary,
                fontSize = 10.sp,
            )
        }
    }
}

private fun initialsFor(title: String): String {
    val words = title.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return "?"
    if (words.size == 1) return words[0].first().uppercaseChar().toString()
    return "${words[0].first()}${words[1].first()}".uppercase()
}
