package com.strata.tv.ui.setup

import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.Image
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.strata.tv.R
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.strata.tv.data.repo.SyncService
import com.strata.tv.data.tmdb.EnrichmentProgressTracker
import com.strata.tv.ui.theme.StrataColors

/**
 * Full-screen first-launch sync progress.
 *
 * Shows three phases the user actually cares about:
 *  1. Downloading from your provider
 *  2. Sorting and categorising
 *  3. Fetching artwork and details from TMDB
 *
 * "Sync in background" drops them into the main UI; sync keeps going.
 * The screen auto-dismisses when the sync stage reaches Done.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SyncProgressScreen(
    progress: SyncService.Progress,
    enrichment: EnrichmentProgressTracker.Progress,
    onSkipToBackground: () -> Unit,
) {
    val phases = listOf(
        Phase(
            id = "download",
            title = "Downloading from your provider",
            subtitle = "Pulling your IPTV playlist",
        ),
        Phase(
            id = "sort",
            title = "Sorting your library",
            subtitle = "Splitting into live channels, films and series",
        ),
        Phase(
            id = "enrich",
            title = "Filtering and adding details",
            subtitle = "Looking up TMDB for artwork and dropping anything not in your selected languages",
        ),
    )
    val currentPhase = currentPhase(progress, enrichment)
    val fraction = overallFraction(progress, enrichment)
    val animatedFraction by animateFloatAsState(targetValue = fraction, label = "sync-progress")

    // The "Sync in background" button is the only focusable thing on
    // this screen; the D-pad has nowhere else to go.  Request focus
    // once on first composition so the user can press OK / Center
    // immediately instead of being stuck on a non-interactive screen.
    val skipFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        runCatching { skipFocusRequester.requestFocus() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(StrataColors.SurfaceVoid),
        contentAlignment = Alignment.CenterStart,
    ) {
        // -- Full-bleed Strata brand background ------------------------
        // The SVG ships a centered STRATA wordmark + TV icon already,
        // so the foreground content drops the redundant "Strata TV"
        // header and shifts to the left half of the screen so it
        // doesn't fight the centerpiece.
        Image(
            painter = painterResource(R.drawable.splash_bg),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        // Dark gradient overlay on the left third so the progress text
        // has enough contrast against the gradient background.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.65f),
                            Color.Black.copy(alpha = 0.0f),
                        ),
                        startX = 0f,
                        endX = 1200f,
                    ),
                ),
        )

        Column(
            modifier = Modifier.widthIn(max = 680.dp).padding(48.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            Text(
                text = "Building your library",
                color = Color.White,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(24.dp))

            // Overall progress bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(StrataColors.SurfaceRaised),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(animatedFraction.coerceIn(0f, 1f))
                        .height(8.dp)
                        .background(StrataColors.AccentPrimaryBright),
                )
            }

            Spacer(Modifier.height(20.dp))

            // Phase list with status indicators
            phases.forEach { phase ->
                PhaseRow(
                    phase = phase,
                    isActive = phase.id == currentPhase,
                    isDone = phases.indexOfFirst { it.id == currentPhase } > phases.indexOf(phase),
                    detail = if (phase.id == currentPhase) currentDetail(progress, enrichment) else null,
                )
                Spacer(Modifier.height(4.dp))
            }

            Spacer(Modifier.height(24.dp))

            if (progress is SyncService.Progress.Error) {
                Text(
                    text = "Sync failed: ${progress.message}",
                    color = StrataColors.StatusLive,
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(12.dp))
            }
            // Surface enrichment failures the same way (issue #47).
            // Without this, a failed TMDB pass leaves the user staring
            // at a frozen ring with no clue what went wrong.
            enrichment.errorMessage?.let { message ->
                Text(
                    text = "TMDB enrichment failed: $message",
                    color = StrataColors.StatusLive,
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(12.dp))
            }

            Surface(
                onClick = onSkipToBackground,
                modifier = Modifier.focusRequester(skipFocusRequester),
                shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(8.dp)),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = StrataColors.SurfaceFloat,
                    focusedContainerColor = StrataColors.AccentPrimary,
                ),
            ) {
                Text(
                    text = "Sync in background",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                )
            }

            Spacer(Modifier.height(8.dp))

            Text(
                text = "Big libraries take a few minutes the first time. " +
                    "Skip to background if you want to start exploring while it finishes.",
                color = StrataColors.TextTertiary,
                fontSize = 11.sp,
            )
        }
    }
}

private data class Phase(val id: String, val title: String, val subtitle: String)

@Composable
private fun PhaseRow(phase: Phase, isActive: Boolean, isDone: Boolean, detail: String?) {
    Row(verticalAlignment = Alignment.Top) {
        // Status dot
        Box(
            modifier = Modifier
                .padding(top = 6.dp)
                .size(10.dp)
                .clip(CircleShape)
                .background(
                    when {
                        isDone -> StrataColors.AccentPrimary
                        isActive -> StrataColors.AccentPrimaryBright
                        else -> StrataColors.SurfaceOverlay
                    },
                ),
        )
        Spacer(Modifier.size(12.dp))
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = phase.title,
                color = when {
                    isDone -> StrataColors.TextSecondary
                    isActive -> Color.White
                    else -> StrataColors.TextTertiary
                },
                fontSize = 15.sp,
                fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
            )
            Text(
                text = detail ?: phase.subtitle,
                color = StrataColors.TextTertiary,
                fontSize = 12.sp,
            )
        }
    }
}

// ---------------------------------------------------------------------
// Phase + progress helpers
// ---------------------------------------------------------------------

private fun currentPhase(
    progress: SyncService.Progress,
    enrichment: EnrichmentProgressTracker.Progress,
): String = when {
    progress is SyncService.Progress.Done && enrichment.isRunning -> "enrich"
    progress is SyncService.Progress.Done -> "enrich"
    progress is SyncService.Progress.PostProcessing -> "sort"
    progress is SyncService.Progress.Parsing -> "sort"
    progress is SyncService.Progress.Downloading -> "download"
    progress is SyncService.Progress.Error -> "download"
    else -> "download"
}

private fun overallFraction(
    progress: SyncService.Progress,
    enrichment: EnrichmentProgressTracker.Progress,
): Float {
    // Sync phase is the first 50%, enrichment is the rest.
    val syncSlice = when (progress) {
        SyncService.Progress.Idle -> 0f
        is SyncService.Progress.Downloading -> 0.05f
        is SyncService.Progress.Parsing -> {
            0.10f + 0.30f * (progress.parsed / 30_000f).coerceAtMost(1f)
        }
        SyncService.Progress.PostProcessing -> 0.45f
        is SyncService.Progress.Done -> 0.50f
        is SyncService.Progress.Error -> 0f
    }
    val enrichSlice = if (progress is SyncService.Progress.Done) {
        0.50f * enrichment.fraction
    } else 0f
    return syncSlice + enrichSlice
}

private fun currentDetail(
    progress: SyncService.Progress,
    enrichment: EnrichmentProgressTracker.Progress,
): String? = when {
    progress is SyncService.Progress.Done && enrichment.isRunning ->
        "${enrichment.processed} of ${enrichment.total} filtered and enriched " +
            "(${enrichment.percent}%)"
    progress is SyncService.Progress.Done && !enrichment.isRunning &&
        enrichment.enrichmentHasStarted ->
        "Done"
    progress is SyncService.Progress.Done && !enrichment.isRunning ->
        "Starting TMDB lookup..."
    progress is SyncService.Progress.PostProcessing ->
        "Indexing, de-duplicating, applying your filters"
    progress is SyncService.Progress.Parsing ->
        "${progress.parsed} entries parsed so far"
    progress is SyncService.Progress.Downloading ->
        "Connecting to provider..."
    progress is SyncService.Progress.Error ->
        progress.message
    else -> null
}
