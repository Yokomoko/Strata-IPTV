package com.strata.tv.ui.live

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.foundation.lazy.list.TvLazyColumn
import androidx.tv.foundation.lazy.list.TvLazyRow
import androidx.tv.foundation.lazy.list.items
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.strata.tv.ui.nav.AppNavState
import com.strata.tv.ui.nav.PlayerArgs
import com.strata.tv.ui.theme.StrataColors
import java.time.Duration
import java.time.Instant

/**
 * Live TV screen — cinematic header + category chips + 1D channel list
 * with an optional EPG grid view toggle.
 *
 * Phase 9: upgraded header with gradient and pulsing live dot,
 * polished channel rows with focus-aware elevation, better chip styling.
 * EPG agent: added GuideGridScreen with horizontal programme timeline.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun LiveScreen(
    modifier: Modifier = Modifier,
    onNavigate: AppNavState? = null,
    viewModel: LiveViewModel = hiltViewModel(),
    onPlayChannel: (ChannelWithGuide) -> Unit = {},
) {
    val state by viewModel.state.collectAsState()
    val epgLoading by viewModel.epgLoading.collectAsState()
    var showGrid by remember { mutableStateOf(false) }

    // Refresh now/next EPG data whenever the Live tab is (re)composed (#18).
    LaunchedEffect(Unit) {
        viewModel.refreshOnResume()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(StrataColors.SurfaceVoid),
    ) {
        // Cinematic header with grid toggle
        LiveHeader(
            channelCount = state.channels.size,
            isLoading = epgLoading,
            showGrid = showGrid,
            onToggleGrid = { showGrid = !showGrid },
            lastRefreshed = state.lastRefreshed,
        )

        // Category chips
        if (state.categories.size > 1) {
            CategoryChips(
                categories = state.categories,
                selected = state.selectedCategory,
                onSelected = { viewModel.selectCategory(it) },
            )
        }

        Spacer(Modifier.height(8.dp))

        // Channel list or grid
        if (state.channels.isEmpty()) {
            LoadingState()
        } else if (showGrid) {
            GuideGridScreen(
                channels = state.channels,
                programmeDao = viewModel.programmeDao,
                onPlayChannel = onPlayChannel,
            )
        } else {
            TvLazyColumn(
                contentPadding = PaddingValues(
                    start = 40.dp,
                    end = 24.dp,
                    bottom = 24.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(
                    items = state.channels,
                    key = { it.channelEntity.contentId },
                ) { channel ->
                    ChannelRow(
                        channel = channel,
                        onPlay = {
                            onNavigate?.openPlayer(
                                PlayerArgs(
                                    streamUrl = channel.streamUrl,
                                    title = channel.displayName,
                                    isLive = true,
                                    contentType = "live",
                                    artworkUrl = channel.logoUrl,
                                ),
                            )
                        },
                    )
                }
            }
        }
    }
}

// -------------------------------------------------------------------------
// Cinematic header with gradient, pulsing live dot, and grid toggle
// -------------------------------------------------------------------------

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun LiveHeader(
    channelCount: Int,
    isLoading: Boolean,
    showGrid: Boolean,
    onToggleGrid: () -> Unit,
    lastRefreshed: Instant? = null,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(100.dp)
            .background(
                Brush.verticalGradient(
                    colorStops = arrayOf(
                        0.0f to Color(0xFF1A0A0A),
                        0.6f to Color(0xFF120808),
                        1.0f to StrataColors.SurfaceVoid,
                    ),
                ),
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp, vertical = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Pulsing live dot
            PulsingLiveDot()
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "TV Guide",
                    color = StrataColors.TextPrimary,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.3).sp,
                )
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (isLoading) "Loading guide data\u2026" else "$channelCount channels",
                        color = StrataColors.TextTertiary,
                        fontSize = 13.sp,
                    )
                    // "Updated X min ago" timestamp (#18)
                    if (!isLoading && lastRefreshed != null) {
                        val agoText = formatTimeAgo(lastRefreshed)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = "\u00B7",
                            color = StrataColors.TextTertiary,
                            fontSize = 13.sp,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "Updated $agoText",
                            color = StrataColors.TextTertiary,
                            fontSize = 12.sp,
                        )
                    }
                }
            }

            // List / Grid toggle
            Surface(
                onClick = onToggleGrid,
                shape = ClickableSurfaceDefaults.shape(
                    shape = RoundedCornerShape(16.dp),
                ),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = StrataColors.SurfaceRaised,
                    focusedContainerColor = StrataColors.AccentPrimaryBright,
                ),
                modifier = Modifier.height(32.dp),
            ) {
                Text(
                    text = if (showGrid) "List View" else "Grid View",
                    color = StrataColors.TextSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun PulsingLiveDot() {
    val transition = rememberInfiniteTransition(label = "live-dot")
    val scale by transition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "live-dot-scale",
    )
    Box(
        modifier = Modifier
            .size(14.dp)
            .scale(scale)
            .clip(CircleShape)
            .background(StrataColors.StatusLive),
    )
}

// -------------------------------------------------------------------------
// Category chips -- upgraded styling
// -------------------------------------------------------------------------

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CategoryChips(
    categories: List<String>,
    selected: String,
    onSelected: (String) -> Unit,
) {
    TvLazyRow(
        contentPadding = PaddingValues(horizontal = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(items = categories, key = { it }) { category ->
            val isSelected = category == selected
            val shape = RoundedCornerShape(20.dp)
            var chipFocused by remember { mutableStateOf(false) }
            Surface(
                onClick = { onSelected(category) },
                shape = ClickableSurfaceDefaults.shape(shape = shape),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = if (isSelected) StrataColors.AccentPrimary else StrataColors.SurfaceRaised,
                    focusedContainerColor = StrataColors.AccentPrimary,
                ),
                modifier = Modifier
                    .height(36.dp)
                    .onFocusChanged { chipFocused = it.isFocused }
                    .then(
                        if (!isSelected && !chipFocused) Modifier.border(1.dp, StrataColors.SurfaceFloat, shape)
                        else Modifier,
                    ),
            ) {
                Text(
                    text = category,
                    color = if (isSelected || chipFocused) Color.White else StrataColors.TextSecondary,
                    fontSize = 13.sp,
                    fontWeight = if (isSelected || chipFocused) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
                )
            }
        }
    }
}

// -------------------------------------------------------------------------
// Channel row -- polished with rounded corners
// -------------------------------------------------------------------------

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ChannelRow(
    channel: ChannelWithGuide,
    onPlay: () -> Unit,
) {
    Surface(
        onClick = onPlay,
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = StrataColors.SurfaceBase,
            focusedContainerColor = StrataColors.SurfaceRaised,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .height(76.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ChannelLogo(
                logoUrl = channel.logoUrl,
                displayName = channel.displayName,
            )
            Spacer(Modifier.width(16.dp))

            Column(modifier = Modifier.width(180.dp)) {
                if (channel.channelNumber != null) {
                    Text(
                        text = channel.channelNumber.toString(),
                        color = StrataColors.TextTertiary,
                        fontSize = 10.sp,
                    )
                }
                Text(
                    text = channel.displayName,
                    color = StrataColors.TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.width(16.dp))

            // Vertical divider
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(40.dp)
                    .background(StrataColors.SurfaceFloat),
            )
            Spacer(Modifier.width(16.dp))

            NowNextColumn(
                label = "NOW",
                labelColor = StrataColors.StatusLive,
                title = channel.nowTitle,
                modifier = Modifier.weight(1f),
            )

            Spacer(Modifier.width(12.dp))

            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(40.dp)
                    .background(StrataColors.SurfaceFloat),
            )
            Spacer(Modifier.width(16.dp))

            NowNextColumn(
                label = "NEXT",
                labelColor = StrataColors.AccentSecondary,
                title = channel.nextTitle,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

// -------------------------------------------------------------------------
// Sub-composables
// -------------------------------------------------------------------------

@Composable
internal fun ChannelLogo(logoUrl: String, displayName: String) {
    if (logoUrl.isNotBlank()) {
        AsyncImage(
            model = logoUrl,
            contentDescription = displayName,
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(8.dp)),
        )
    } else {
        val tileColor = LOGO_PALETTE[
            (displayName.hashCode() and Int.MAX_VALUE) % LOGO_PALETTE.size
        ]
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(tileColor),
            contentAlignment = Alignment.Center,
        ) {
            val initials = displayName.trim()
                .split(Regex("\\s+"))
                .take(2)
                .joinToString("") {
                    it.firstOrNull()?.uppercaseChar()?.toString() ?: ""
                }
                .ifEmpty { "?" }
            Text(
                text = initials,
                color = StrataColors.TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun NowNextColumn(
    label: String,
    labelColor: Color,
    title: String?,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            color = labelColor,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = title ?: "No guide data",
            color = if (title != null) StrataColors.TextPrimary else StrataColors.TextTertiary,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun LoadingState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "Loading TV Guide\u2026",
                color = StrataColors.TextSecondary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Building channel list and guide data",
                color = StrataColors.TextTertiary,
                fontSize = 13.sp,
            )
        }
    }
}

/**
 * Formats a timestamp as a human-readable "X min ago" / "X hr ago"
 * string, suitable for the EPG "Updated ..." label (#18).
 */
private fun formatTimeAgo(instant: Instant): String {
    val elapsed = Duration.between(instant, Instant.now())
    val minutes = elapsed.toMinutes()
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        else -> {
            val hours = elapsed.toHours()
            if (hours == 1L) "1 hr ago" else "$hours hrs ago"
        }
    }
}

internal val LOGO_PALETTE = listOf(
    Color(0xFF3F2A56),
    Color(0xFF1F4068),
    Color(0xFF4F2828),
    Color(0xFF1B5E20),
    Color(0xFF4A148C),
    Color(0xFF263238),
    Color(0xFF5D4037),
    Color(0xFF1A237E),
)
