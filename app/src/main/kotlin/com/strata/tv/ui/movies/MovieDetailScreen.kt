package com.strata.tv.ui.movies

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.strata.tv.data.db.ContentItemEntity
import com.strata.tv.data.db.MovieEntity
import com.strata.tv.ui.nav.PlayerArgs
import com.strata.tv.ui.theme.StrataColors
import com.strata.tv.ui.widgets.GenreChips
import androidx.compose.ui.platform.LocalContext
import com.strata.tv.ui.widgets.TrailerWebView
import com.strata.tv.ui.widgets.MetadataChip
import com.strata.tv.ui.widgets.ShimmerHero

/**
 * Movie detail screen -- full-bleed backdrop, poster thumbnail,
 * metadata, Play CTA, watchlist toggle.
 *
 * Follows the Netflix / Disney+ detail page pattern for TV apps.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun MovieDetailScreen(
    contentId: String,
    onBack: () -> Unit,
    onPlay: (PlayerArgs) -> Unit,
    viewModel: MovieDetailViewModel = hiltViewModel(),
) {
    LaunchedEffect(contentId) { viewModel.load(contentId) }
    val state by viewModel.state.collectAsState()

    BackHandler { onBack() }

    when (val s = state) {
        MovieDetailState.Loading -> {
            Box(Modifier.fillMaxSize().background(StrataColors.SurfaceVoid)) {
                ShimmerHero(height = 500)
            }
        }
        MovieDetailState.NotFound -> {
            Box(
                Modifier.fillMaxSize().background(StrataColors.SurfaceVoid),
                contentAlignment = Alignment.Center,
            ) {
                Text("Movie not found", color = StrataColors.TextSecondary, fontSize = 16.sp)
            }
        }
        is MovieDetailState.Loaded -> {
            MovieDetailContent(
                movie = s.movie,
                content = s.content,
                isOnWatchlist = s.isOnWatchlist,
                onBack = onBack,
                onPlay = onPlay,
                onToggleWatchlist = { viewModel.toggleWatchlist() },
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun MovieDetailContent(
    movie: MovieEntity,
    content: ContentItemEntity,
    isOnWatchlist: Boolean,
    onBack: () -> Unit,
    onPlay: (PlayerArgs) -> Unit,
    onToggleWatchlist: () -> Unit,
) {
    val scrollState = rememberScrollState()
    var showTrailer by remember { mutableStateOf(false) }

    // Auto-focus the Play button when the detail screen renders so the
    // user can hit OK without pressing Down first.  Keyed on the movie
    // id so navigating between detail screens re-requests focus.
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(movie.contentId) {
        runCatching { playFocus.requestFocus() }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // -- Full-bleed backdrop -----------------------------------------
        val backdropUrl = movie.backdropUrl.takeIf { it.isNotBlank() }
            ?: movie.posterUrl.takeIf { it.isNotBlank() }
        if (backdropUrl != null) {
            AsyncImage(
                model = backdropUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(480.dp),
            )
        }

        // Gradient overlays
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(480.dp)
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
                .height(480.dp)
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
            ) {
                // Poster thumbnail
                val posterUrl = movie.posterUrl.takeIf { it.isNotBlank() }
                if (posterUrl != null) {
                    AsyncImage(
                        model = posterUrl,
                        contentDescription = movie.movieTitle,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .width(160.dp)
                            .height(240.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .border(2.dp, StrataColors.SurfaceFloat, RoundedCornerShape(12.dp)),
                    )
                    Spacer(Modifier.width(24.dp))
                }

                // Title + metadata column
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = movie.movieTitle,
                        color = Color.White,
                        fontSize = 36.sp,
                        fontWeight = FontWeight.Bold,
                        lineHeight = 42.sp,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        letterSpacing = (-0.5).sp,
                    )

                    Spacer(Modifier.height(10.dp))

                    // Year, runtime, rating metadata row
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        movie.year?.let { MetadataChip(it.toString()) }
                        movie.runtime?.let { MetadataChip("${it}m") }
                        if (movie.rating > 0.0) {
                            MetadataChip("\u2605 ${String.format("%.1f", movie.rating)}")
                        }
                        // Phase 8: certification field
                        val cert = getCertification(movie)
                        if (cert.isNotBlank()) {
                            MetadataChip(cert)
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    // Genre chips
                    GenreChips(genres = movie.genre)

                    Spacer(Modifier.height(16.dp))

                    // Overview / plot
                    val overview = getOverview(movie)
                    if (overview.isNotBlank()) {
                        Text(
                            text = overview,
                            color = StrataColors.TextSecondary,
                            fontSize = 14.sp,
                            lineHeight = 22.sp,
                            maxLines = 6,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(16.dp))
                    }

                    // Cast
                    val cast = getCast(movie)
                    if (cast.isNotBlank()) {
                        Text(
                            text = "Cast",
                            color = StrataColors.TextTertiary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = cast,
                            color = StrataColors.TextSecondary,
                            fontSize = 13.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(16.dp))
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            // -- Action buttons ------------------------------------------
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Play button
                Surface(
                    onClick = {
                        onPlay(
                            PlayerArgs(
                                streamUrl = content.streamUrl,
                                title = movie.movieTitle,
                                isLive = false,
                                resumePositionMs = movie.resumePositionMs,
                                contentType = "movie",
                                artworkUrl = movie.posterUrl,
                                contentId = movie.contentId,
                            ),
                        )
                    },
                    shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(8.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = StrataColors.AccentPrimary,
                        focusedContainerColor = StrataColors.AccentPrimaryBright,
                    ),
                    modifier = Modifier
                        .height(48.dp)
                        .focusRequester(playFocus),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 28.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (movie.resumePositionMs > 0) "\u25B6  Resume" else "\u25B6  Play",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }

                // Watch Trailer
                if (movie.trailerUrl.isNotBlank()) {
                    Surface(
                        onClick = { showTrailer = true },
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
                                text = "\u25B6  Trailer",
                                color = StrataColors.TextPrimary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }
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
        }

        // -- Trailer overlay ---------------------------------------------
        // The YouTube IFrame embed (even on the youtube-nocookie domain)
        // routinely refuses to play with "Video player configuration
        // error 153" because many channels have embedded playback
        // disabled.  The robust path is to hand the video off to the
        // YouTube app via an Intent — Fire Stick always has it
        // installed and its player Just Works.  We fall back to the
        // WebView overlay only if the YouTube app isn't available.
        val context = LocalContext.current
        LaunchedEffect(showTrailer) {
            if (!showTrailer || movie.trailerUrl.isBlank()) return@LaunchedEffect
            val youtubeKey = movie.trailerUrl.substringAfter("v=").substringBefore("&")
            val ytAppIntent = android.content.Intent(
                android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse("vnd.youtube:$youtubeKey"),
            ).apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) }
            val launched = runCatching { context.startActivity(ytAppIntent) }.isSuccess
            if (!launched) {
                // Try the standard https URL — picks up any browser /
                // alternative YT app.
                runCatching {
                    context.startActivity(
                        android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://www.youtube.com/watch?v=$youtubeKey"),
                        ).apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) },
                    )
                }
            }
            // Drop the overlay flag immediately — the YouTube app takes
            // over the screen, the user comes back to the detail screen
            // when they finish or back out.
            showTrailer = false
        }
    }
}

// Phase 8 fields live on MovieEntity, not ContentItemEntity.
private fun getOverview(movie: MovieEntity): String = movie.overview
private fun getCast(movie: MovieEntity): String = movie.cast
private fun getCertification(movie: MovieEntity): String = movie.certification
