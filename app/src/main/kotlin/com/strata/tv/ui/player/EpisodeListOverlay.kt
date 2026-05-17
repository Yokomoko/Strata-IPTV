package com.strata.tv.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.strata.tv.data.db.EpisodeEntity
import com.strata.tv.ui.theme.StrataColors

/**
 * Bottom-anchored episode-list overlay shown inside the player when
 * the user presses D-pad Down on a show.  Lists every episode in the
 * current series; the user can navigate with D-pad Left/Right (TV
 * focus traversal) and press Select to jump to a different episode.
 *
 * Auto-scrolls to the *currently playing* episode on first show so
 * the user never has to scroll back to find their place.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun EpisodeListOverlay(
    visible: Boolean,
    episodes: List<EpisodeEntity>,
    currentSeason: Int?,
    currentEpisode: Int?,
    onEpisodeSelected: (EpisodeEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible && episodes.isNotEmpty(),
        enter = fadeIn(tween(220)) + slideInVertically(
            animationSpec = tween(260),
            initialOffsetY = { it },
        ),
        exit = fadeOut(tween(180)) + slideOutVertically(
            animationSpec = tween(220),
            targetOffsetY = { it },
        ),
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color(0xCC000000),
                            Color(0xF2000000),
                        ),
                    ),
                )
                .padding(start = 24.dp, end = 24.dp, top = 36.dp, bottom = 28.dp),
        ) {
            Column {
                // Header
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "EPISODES",
                        color = StrataColors.AccentSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.6.sp,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "${episodes.size} total",
                        color = StrataColors.TextTertiary,
                        fontSize = 11.sp,
                    )
                    Spacer(Modifier.width(16.dp))
                    Text(
                        text = "Back to dismiss",
                        color = StrataColors.TextTertiary,
                        fontSize = 11.sp,
                    )
                }

                Spacer(Modifier.height(12.dp))

                // Episode rail
                val listState = rememberLazyListState()
                val currentIndex = remember(episodes, currentSeason, currentEpisode) {
                    episodes.indexOfFirst {
                        it.seasonNumber == currentSeason && it.episodeNumber == currentEpisode
                    }.coerceAtLeast(0)
                }
                LaunchedEffect(visible, currentIndex) {
                    if (visible && episodes.isNotEmpty()) {
                        // Centre the currently playing episode in view.
                        listState.scrollToItem(currentIndex)
                    }
                }

                LazyRow(
                    state = listState,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(end = 24.dp),
                ) {
                    items(
                        items = episodes,
                        key = { ep ->
                            ep.contentId.ifBlank { "${ep.seasonNumber}-${ep.episodeNumber}" }
                        },
                    ) { ep ->
                        val isCurrent = ep.seasonNumber == currentSeason && ep.episodeNumber == currentEpisode
                        EpisodeCard(
                            episode = ep,
                            isCurrent = isCurrent,
                            requestInitialFocus = isCurrent,
                            onClick = { onEpisodeSelected(ep) },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun EpisodeCard(
    episode: EpisodeEntity,
    isCurrent: Boolean,
    requestInitialFocus: Boolean,
    onClick: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(requestInitialFocus) {
        if (requestInitialFocus) {
            // Defer slightly so the LazyRow has time to lay out the
            // item we want to focus.  Without this the FocusRequester
            // can throw "is not initialized" on slow Fire Stick boots.
            try {
                focusRequester.requestFocus()
            } catch (_: IllegalStateException) {
                // Item not laid out yet — focus falls through to first card.
            }
        }
    }

    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (isCurrent) StrataColors.SurfaceFloat else StrataColors.SurfaceRaised,
            focusedContainerColor = StrataColors.AccentPrimary,
        ),
        modifier = Modifier
            .width(220.dp)
            .height(110.dp)
            .focusRequester(focusRequester),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            // Top row: SxEy badge + status pill
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(
                            if (isCurrent) StrataColors.AccentPrimary
                            else StrataColors.SurfaceOverlay,
                        )
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = "S${episode.seasonNumber}E${episode.episodeNumber}",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.width(8.dp))
                when {
                    isCurrent -> StatusPill(
                        label = "NOW PLAYING",
                        bg = StrataColors.AccentSecondary,
                    )
                    episode.watched -> StatusPill(
                        label = "WATCHED",
                        bg = StrataColors.StatusSuccess,
                    )
                    episode.resumePositionMs > 0 -> StatusPill(
                        label = "RESUME",
                        bg = StrataColors.AccentPrimaryBright,
                    )
                }
            }

            // Episode title
            Text(
                text = episode.episodeTitle.ifBlank { "Episode ${episode.episodeNumber}" },
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            // Runtime footer (if known)
            if (episode.runtime != null && episode.runtime > 0) {
                Text(
                    text = "${episode.runtime} min",
                    color = StrataColors.TextSecondary,
                    fontSize = 10.sp,
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun StatusPill(label: String, bg: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(3.dp))
            .background(bg)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = label,
            color = Color.White,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.6.sp,
        )
    }
}

