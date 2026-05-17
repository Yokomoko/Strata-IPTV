package com.strata.tv.ui.live

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.foundation.lazy.list.TvLazyColumn
import androidx.tv.foundation.lazy.list.TvLazyRow
import androidx.tv.foundation.lazy.list.items
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.strata.tv.data.db.ProgrammeDao
import com.strata.tv.data.db.ProgrammeEntity
import com.strata.tv.ui.theme.StrataColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * EPG grid view — a per-channel horizontal programme timeline.
 *
 * Displays channels vertically on the left with a scrollable
 * horizontal programme timeline for each.  D-pad Right moves
 * forward in time within a channel's timeline; D-pad Up/Down
 * moves between channels.
 *
 * The grid queries a wider time window ([LiveViewModel.GRID_WINDOW_HOURS])
 * than the Now/Next view to provide a meaningful browsing experience.
 *
 * Focus model:
 * - Each programme cell is a focusable [Surface].
 * - The [TvLazyRow] per channel handles horizontal D-pad navigation.
 * - The outer [TvLazyColumn] handles vertical channel switching.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun GuideGridScreen(
    channels: List<ChannelWithGuide>,
    programmeDao: ProgrammeDao,
    onPlayChannel: (ChannelWithGuide) -> Unit = {},
) {
    val now = remember { Instant.now() }
    val windowEnd = remember { now.plus(LiveViewModel.GRID_WINDOW_HOURS, ChronoUnit.HOURS) }

    // Load the extended programme data for the grid window.
    var programmesByChannel by remember {
        mutableStateOf<Map<String, List<ProgrammeEntity>>>(emptyMap())
    }

    LaunchedEffect(channels, windowEnd) {
        // Query + group off the main thread.  A 12h EPG window over
        // hundreds of channels can produce 20k+ rows; the .groupBy hashing
        // cost was running on Dispatchers.Main.immediate and causing grid
        // open jank (perf review #4).
        val grouped = withContext(Dispatchers.Default) {
            programmeDao.inRange(now, windowEnd).groupBy { it.channelId }
        }
        programmesByChannel = grouped
    }

    // Time header + channel rows
    Column(modifier = Modifier.fillMaxSize()) {
        // Time axis header
        TimeHeader(startTime = now, hours = LiveViewModel.GRID_WINDOW_HOURS.toInt())

        // Channel rows with programme timelines
        TvLazyColumn(
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(
                items = channels,
                key = { it.channelEntity.contentId },
            ) { channel ->
                val progs = if (channel.xmltvChannelId != null) {
                    programmesByChannel[channel.xmltvChannelId] ?: emptyList()
                } else {
                    emptyList()
                }

                GuideGridRow(
                    channel = channel,
                    programmes = progs,
                    windowStart = now,
                    windowEnd = windowEnd,
                    onPlayChannel = { onPlayChannel(channel) },
                )
            }
        }
    }
}

// -------------------------------------------------------------------------
// Time header — shows hour markers across the top
// -------------------------------------------------------------------------

@Composable
private fun TimeHeader(startTime: Instant, hours: Int) {
    val zone = remember { ZoneId.systemDefault() }
    val formatter = remember { DateTimeFormatter.ofPattern("HH:mm").withZone(zone) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp)
            .padding(start = CHANNEL_LABEL_WIDTH + 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (h in 0 until hours) {
            val time = startTime.plus(h.toLong(), ChronoUnit.HOURS)
            Box(
                modifier = Modifier.width(HOUR_WIDTH),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    text = formatter.format(time),
                    color = StrataColors.TextTertiary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }

    // Thin separator line
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(StrataColors.SurfaceFloat),
    )
}

// -------------------------------------------------------------------------
// Single channel grid row
// -------------------------------------------------------------------------

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun GuideGridRow(
    channel: ChannelWithGuide,
    programmes: List<ProgrammeEntity>,
    windowStart: Instant,
    windowEnd: Instant,
    onPlayChannel: () -> Unit,
) {
    val now = remember { Instant.now() }
    val windowDurationMinutes = ChronoUnit.MINUTES.between(windowStart, windowEnd)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(GRID_ROW_HEIGHT)
            .background(StrataColors.SurfaceBase),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Channel label (logo + name)
        Surface(
            onClick = onPlayChannel,
            shape = ClickableSurfaceDefaults.shape(
                shape = RoundedCornerShape(4.dp),
            ),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = StrataColors.SurfaceRaised,
                focusedContainerColor = StrataColors.AccentPrimaryBright,
            ),
            modifier = Modifier
                .width(CHANNEL_LABEL_WIDTH)
                .fillMaxHeight(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ChannelLogo(
                    logoUrl = channel.logoUrl,
                    displayName = channel.displayName,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = channel.displayName,
                    color = StrataColors.TextPrimary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Spacer(Modifier.width(4.dp))

        // Programme timeline — horizontal scrollable row
        if (programmes.isEmpty()) {
            // No guide data for this channel
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(4.dp))
                    .background(StrataColors.SurfaceBase)
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    text = "No guide data",
                    color = StrataColors.TextTertiary,
                    fontSize = 11.sp,
                )
            }
        } else {
            // Sort programmes by start time and clip to window.
            val sortedProgs = remember(programmes) {
                programmes
                    .filter { it.endTime > windowStart && it.startTime < windowEnd }
                    .sortedBy { it.startTime }
            }

            TvLazyRow(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                contentPadding = PaddingValues(horizontal = 4.dp),
            ) {
                items(
                    items = sortedProgs,
                    key = { "${it.channelId}_${it.startTime}" },
                ) { prog ->
                    ProgrammeCell(
                        programme = prog,
                        windowStart = windowStart,
                        windowEnd = windowEnd,
                        windowDurationMinutes = windowDurationMinutes,
                        isNow = prog.startTime <= now && prog.endTime > now,
                    )
                }
            }
        }
    }
}

// -------------------------------------------------------------------------
// Programme cell — a single programme block in the timeline
// -------------------------------------------------------------------------

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ProgrammeCell(
    programme: ProgrammeEntity,
    windowStart: Instant,
    windowEnd: Instant,
    windowDurationMinutes: Long,
    isNow: Boolean,
) {
    val zone = remember { ZoneId.systemDefault() }
    val timeFormatter = remember { DateTimeFormatter.ofPattern("HH:mm").withZone(zone) }

    // Clamp programme to window bounds for width calculation.
    val visibleStart = maxOf(programme.startTime, windowStart)
    val visibleEnd = minOf(programme.endTime, windowEnd)
    val durationMinutes = ChronoUnit.MINUTES.between(visibleStart, visibleEnd)
        .coerceAtLeast(1) // avoid zero-width

    // Width proportional to duration: HOUR_WIDTH * (duration / 60).
    val cellWidth = (HOUR_WIDTH * durationMinutes.toFloat() / 60f)

    Surface(
        onClick = { /* future: show programme details */ },
        shape = ClickableSurfaceDefaults.shape(
            shape = RoundedCornerShape(4.dp),
        ),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (isNow) {
                StrataColors.SurfaceFloat
            } else {
                StrataColors.SurfaceRaised
            },
            focusedContainerColor = StrataColors.AccentPrimary,
        ),
        modifier = Modifier
            .width(cellWidth)
            .fillMaxHeight()
            .padding(vertical = 2.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            // Programme title
            Text(
                text = programme.title.ifBlank { "No title" },
                color = StrataColors.TextPrimary,
                fontSize = 11.sp,
                fontWeight = if (isNow) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            // Time range (only if cell is wide enough)
            if (durationMinutes >= 15) {
                Text(
                    text = "${timeFormatter.format(programme.startTime)} - ${timeFormatter.format(programme.endTime)}",
                    color = StrataColors.TextTertiary,
                    fontSize = 9.sp,
                    maxLines = 1,
                )
            }

            // "NOW" badge
            if (isNow) {
                Text(
                    text = "NOW",
                    color = StrataColors.StatusLive,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                )
            }
        }
    }
}

// -------------------------------------------------------------------------
// Layout constants
// -------------------------------------------------------------------------

/** Width of the channel label column on the left. */
private val CHANNEL_LABEL_WIDTH = 180.dp

/** Width per hour in the timeline.  Tuned for Fire Stick's 1920px. */
private val HOUR_WIDTH = 200.dp

/** Height of each channel row in the grid. */
private val GRID_ROW_HEIGHT = 64.dp
