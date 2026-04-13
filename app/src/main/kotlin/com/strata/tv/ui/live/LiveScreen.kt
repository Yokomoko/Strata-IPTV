package com.strata.tv.ui.live

import androidx.compose.foundation.background
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.strata.tv.ui.theme.StrataColors

/**
 * Live TV screen — 1D vertical list of channels with Now/Next info.
 *
 * Deliberately uses a flat [TvLazyColumn] instead of a 2D scrollable
 * grid.  The Flutter v1 TV Guide used a 2D grid and suffered from
 * endless D-pad navigation problems.  This layout gives trivial focus:
 * up/down between channels, right/select to play.
 *
 * Layout per row:
 * ```
 * [Logo] BBC One                    | NOW: BBC News at Six             | NEXT: The Repair Shop
 * ```
 *
 * Category chips are rendered as a horizontal [TvLazyRow] above the
 * channel list, filtering the displayed channels.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun LiveScreen(
    modifier: Modifier = Modifier,
    viewModel: LiveViewModel = hiltViewModel(),
    onPlayChannel: (ChannelWithGuide) -> Unit = {},
) {
    val state by viewModel.state.collectAsState()
    val epgLoading by viewModel.epgLoading.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(StrataColors.SurfaceVoid),
    ) {
        // Header
        LiveHeader(
            channelCount = state.channels.size,
            isLoading = epgLoading,
        )

        // Category chips
        if (state.categories.size > 1) {
            CategoryChips(
                categories = state.categories,
                selected = state.selectedCategory,
                onSelected = { viewModel.selectCategory(it) },
            )
        }

        Spacer(Modifier.height(4.dp))

        // Channel list — the 1D TV Guide
        if (state.channels.isEmpty() && !epgLoading) {
            EmptyState()
        } else {
            TvLazyColumn(
                contentPadding = PaddingValues(
                    start = 24.dp,
                    end = 24.dp,
                    bottom = 24.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(
                    items = state.channels,
                    key = { it.channelEntity.contentId },
                ) { channel ->
                    ChannelRow(channel, onClick = { onPlayChannel(channel) })
                }
            }
        }
    }
}

// -------------------------------------------------------------------------
// Header
// -------------------------------------------------------------------------

@Composable
private fun LiveHeader(channelCount: Int, isLoading: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Red "LIVE" indicator dot
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(StrataColors.StatusLive),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = "TV Guide",
            color = StrataColors.TextPrimary,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = if (isLoading) "Loading guide data..." else "$channelCount channels",
            color = StrataColors.TextTertiary,
            fontSize = 13.sp,
        )
    }
}

// -------------------------------------------------------------------------
// Category chips
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
            Surface(
                onClick = { onSelected(category) },
                shape = ClickableSurfaceDefaults.shape(
                    shape = RoundedCornerShape(16.dp),
                ),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = if (isSelected) {
                        StrataColors.AccentPrimary
                    } else {
                        StrataColors.SurfaceRaised
                    },
                    focusedContainerColor = StrataColors.AccentPrimaryBright,
                ),
                modifier = Modifier.height(32.dp),
            ) {
                Text(
                    text = category,
                    color = if (isSelected) Color.White else StrataColors.TextSecondary,
                    fontSize = 12.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
        }
    }
}

// -------------------------------------------------------------------------
// Channel row — the core of the 1D TV Guide
// -------------------------------------------------------------------------

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ChannelRow(channel: ChannelWithGuide, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(
            shape = RoundedCornerShape(8.dp),
        ),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = StrataColors.SurfaceBase,
            focusedContainerColor = StrataColors.SurfaceRaised,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Channel logo
            ChannelLogo(
                logoUrl = channel.logoUrl,
                displayName = channel.displayName,
            )

            Spacer(Modifier.width(16.dp))

            // Channel number + name
            Column(
                modifier = Modifier.width(180.dp),
            ) {
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

            // NOW programme
            NowNextColumn(
                label = "NOW",
                labelColor = StrataColors.StatusLive,
                title = channel.nowTitle,
                modifier = Modifier.weight(1f),
            )

            Spacer(Modifier.width(12.dp))

            // Vertical divider
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(40.dp)
                    .background(StrataColors.SurfaceFloat),
            )

            Spacer(Modifier.width(16.dp))

            // NEXT programme
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
private fun ChannelLogo(logoUrl: String, displayName: String) {
    if (logoUrl.isNotBlank()) {
        AsyncImage(
            model = logoUrl,
            contentDescription = displayName,
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(6.dp)),
        )
    } else {
        // Fallback: initials on a coloured tile
        val tileColor = LOGO_PALETTE[
            (displayName.hashCode() and Int.MAX_VALUE) % LOGO_PALETTE.size
        ]
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(6.dp))
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

@Composable
private fun EmptyState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "No channels yet",
                color = StrataColors.TextSecondary,
                fontSize = 16.sp,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Sync your playlist from the Home screen first.",
                color = StrataColors.TextTertiary,
                fontSize = 12.sp,
            )
        }
    }
}

private val LOGO_PALETTE = listOf(
    Color(0xFF3F2A56),
    Color(0xFF1F4068),
    Color(0xFF4F2828),
    Color(0xFF1B5E20),
    Color(0xFF4A148C),
    Color(0xFF263238),
    Color(0xFF5D4037),
    Color(0xFF1A237E),
)
