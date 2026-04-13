package com.strata.tv.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.ListItem
import androidx.tv.material3.ListItemDefaults
import androidx.tv.material3.Text
import com.strata.tv.AppConfig
import com.strata.tv.data.repo.SyncService
import com.strata.tv.ui.theme.StrataColors

/**
 * Settings screen — three card sections (Library, Playlist, About)
 * displayed in a scrollable column.
 *
 * Uses [androidx.tv.material3.ListItem] throughout for TV-friendly
 * focus behaviour.  The "Refresh Library" item triggers a full M3U
 * re-sync and is disabled while one is already in progress.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val sync by viewModel.syncProgress.collectAsState()
    val isSyncing = sync !is SyncService.Progress.Idle &&
        sync !is SyncService.Progress.Done &&
        sync !is SyncService.Progress.Error

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 32.dp, vertical = 24.dp),
    ) {
        // ── Library ──────────────────────────────────────────────
        SectionHeader("Library")
        Card(
            onClick = {},
            shape = CardDefaults.shape(shape = RoundedCornerShape(12.dp)),
            colors = CardDefaults.colors(containerColor = StrataColors.SurfaceRaised),
            modifier = Modifier.fillMaxWidth(),
        ) {
            ListItem(
                selected = false,
                onClick = {},
                headlineContent = {
                    Text(
                        text = "${state.totalCount} items in library",
                        color = StrataColors.TextPrimary,
                    )
                },
                supportingContent = {
                    Text(
                        text = "${state.channelCount} channels \u00B7 ${state.movieCount} movies \u00B7 ${state.seriesCount} series",
                        color = StrataColors.TextSecondary,
                    )
                },
                colors = ListItemDefaults.colors(
                    containerColor = StrataColors.SurfaceRaised,
                    contentColor = StrataColors.TextPrimary,
                    focusedContainerColor = StrataColors.SurfaceFloat,
                    focusedContentColor = StrataColors.TextPrimary,
                ),
            )
        }

        Spacer(Modifier.height(20.dp))

        // ── Playlist ─────────────────────────────────────────────
        SectionHeader("Playlist")
        Card(
            onClick = {},
            shape = CardDefaults.shape(shape = RoundedCornerShape(12.dp)),
            colors = CardDefaults.colors(containerColor = StrataColors.SurfaceRaised),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column {
                ListItem(
                    selected = false,
                    onClick = {},
                    headlineContent = {
                        Text(
                            text = "M3U Playlist",
                            color = StrataColors.TextPrimary,
                        )
                    },
                    supportingContent = {
                        Text(
                            text = maskUrl(AppConfig.PLAYLIST_URL),
                            color = StrataColors.TextSecondary,
                        )
                    },
                    colors = ListItemDefaults.colors(
                        containerColor = StrataColors.SurfaceRaised,
                        contentColor = StrataColors.TextPrimary,
                        focusedContainerColor = StrataColors.SurfaceFloat,
                        focusedContentColor = StrataColors.TextPrimary,
                    ),
                )

                ListItem(
                    selected = false,
                    onClick = {},
                    headlineContent = {
                        Text(
                            text = "EPG Source",
                            color = StrataColors.TextPrimary,
                        )
                    },
                    supportingContent = {
                        Text(
                            text = AppConfig.EPG_URL,
                            color = StrataColors.TextSecondary,
                        )
                    },
                    colors = ListItemDefaults.colors(
                        containerColor = StrataColors.SurfaceRaised,
                        contentColor = StrataColors.TextPrimary,
                        focusedContainerColor = StrataColors.SurfaceFloat,
                        focusedContentColor = StrataColors.TextPrimary,
                    ),
                )

                ListItem(
                    selected = false,
                    onClick = { if (!isSyncing) viewModel.refreshLibrary() },
                    enabled = !isSyncing,
                    headlineContent = {
                        Text(
                            text = if (isSyncing) "Syncing\u2026" else "Refresh Library",
                            color = if (isSyncing) StrataColors.TextTertiary
                            else StrataColors.AccentPrimary,
                        )
                    },
                    supportingContent = {
                        val subtitle = when (sync) {
                            is SyncService.Progress.Downloading -> "Downloading playlist\u2026"
                            is SyncService.Progress.Parsing -> {
                                val p = sync as SyncService.Progress.Parsing
                                "Parsing\u2026 ${p.parsed} entries"
                            }
                            SyncService.Progress.PostProcessing -> "Writing to library\u2026"
                            is SyncService.Progress.Error -> {
                                val e = sync as SyncService.Progress.Error
                                "Failed: ${e.message}"
                            }
                            is SyncService.Progress.Done -> {
                                val d = sync as SyncService.Progress.Done
                                "Done \u2014 ${d.totalParsed} items synced"
                            }
                            SyncService.Progress.Idle -> "Re-download and parse the M3U playlist"
                        }
                        Text(text = subtitle, color = StrataColors.TextSecondary)
                    },
                    colors = ListItemDefaults.colors(
                        containerColor = StrataColors.SurfaceRaised,
                        contentColor = StrataColors.TextPrimary,
                        focusedContainerColor = StrataColors.SurfaceFloat,
                        focusedContentColor = StrataColors.TextPrimary,
                    ),
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        // ── About ────────────────────────────────────────────────
        SectionHeader("About")
        Card(
            onClick = {},
            shape = CardDefaults.shape(shape = RoundedCornerShape(12.dp)),
            colors = CardDefaults.colors(containerColor = StrataColors.SurfaceRaised),
            modifier = Modifier.fillMaxWidth(),
        ) {
            ListItem(
                selected = false,
                onClick = {},
                headlineContent = {
                    Text(
                        text = "Strata TV",
                        color = StrataColors.TextPrimary,
                    )
                },
                supportingContent = {
                    Text(
                        text = "v0.1.0 \u2014 All your telly. Less faff.",
                        color = StrataColors.TextSecondary,
                    )
                },
                colors = ListItemDefaults.colors(
                    containerColor = StrataColors.SurfaceRaised,
                    contentColor = StrataColors.TextPrimary,
                    focusedContainerColor = StrataColors.SurfaceFloat,
                    focusedContentColor = StrataColors.TextPrimary,
                ),
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        color = StrataColors.TextSecondary,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

// Masks a URL to scheme://host/**** — hides the path which
// typically contains auth tokens on IPTV playlist URLs.
private fun maskUrl(url: String): String {
    return try {
        val uri = java.net.URI(url)
        "${uri.scheme}://${uri.host}/****"
    } catch (_: Exception) {
        "****"
    }
}
