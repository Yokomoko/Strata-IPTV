package com.strata.tv.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.strata.tv.AppConfig
import com.strata.tv.data.repo.SyncService
import com.strata.tv.data.settings.AppSettings
import com.strata.tv.data.settings.ProviderConfig
import com.strata.tv.data.settings.SyncFrequency
import com.strata.tv.ui.theme.StrataColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Settings screen.
 *
 * Provider, sync cadence, three filter dimensions (region, category,
 * language), playback prefs.  Lives inside the content area beside
 * the sidebar — the outer Column clips to bounds so the TV-Material
 * focus indication doesn't bleed leftward into the sidebar.
 */
@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val sync by viewModel.syncProgress.collectAsState()
    val accountInfo by viewModel.accountInfo.collectAsState()
    val isSyncing = sync !is SyncService.Progress.Idle &&
        sync !is SyncService.Progress.Done &&
        sync !is SyncService.Progress.Error

    Column(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds() // contains focus glow so it can't extend into sidebar
            .verticalScroll(rememberScrollState())
            // Extra left padding so the focused-state scale on TV
            // surfaces has somewhere to breathe without overlapping
            // the sidebar.
            .padding(start = 48.dp, end = 48.dp, top = 24.dp, bottom = 24.dp),
    ) {
        SectionHeader("Library")
        Card {
            ListRow(
                headline = "${state.totalCount} items in library",
                supporting = "${state.channelCount} channels  ${state.movieCount} movies  ${state.seriesCount} series",
            )
        }

        Spacer(Modifier.height(20.dp))

        SectionHeader("Provider")
        Card {
            Column {
                ListRow(
                    headline = "Provider",
                    supporting = describeProvider(settings.provider),
                )
                if (accountInfo != null) {
                    val info = accountInfo!!
                    val status = if (info.isAuthenticated) "Active" else "Inactive"
                    val expiry = info.expiresAt?.let { formatExpiry(it) } ?: "no expiry"
                    ListRow(
                        headline = "Subscription",
                        supporting = "$status, expires $expiry, ${info.activeConnections}/${info.maxConnections} connections",
                    )
                }
                if (settings.provider.providerId != "custom_m3u" &&
                    settings.provider.providerId.isNotEmpty()
                ) {
                    val on = settings.provider.useFilteredPlaylist
                    ListRow(
                        headline = if (on) "Using filtered playlist" else "Using full catalogue",
                        supporting = if (on) {
                            "Pulling only the channels and films you've ticked " +
                                "on your provider's website. Tap to switch back " +
                                "to the full catalogue."
                        } else {
                            "Pulling your provider's full catalogue. Tap to switch " +
                                "to the personal playlist you curated on the " +
                                "provider's website."
                        },
                        onClick = { viewModel.setUseFilteredPlaylist(!on) },
                    )
                }
                ListRow(
                    headline = "Change provider",
                    supporting = "Reset and re-enter your IPTV credentials",
                    accent = true,
                    onClick = { viewModel.clearProvider() },
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        SectionHeader("Sync")
        Card {
            Column {
                val freq = settings.syncFrequency
                SyncFrequency.entries.forEach { option ->
                    ListRow(
                        headline = option.displayName(),
                        supporting = option.description(),
                        selected = option == freq,
                        onClick = { viewModel.setSyncFrequency(option) },
                    )
                }
                ListRow(
                    headline = if (isSyncing) "Syncing..." else "Refresh library now",
                    supporting = when (sync) {
                        is SyncService.Progress.Downloading -> "Downloading playlist..."
                        is SyncService.Progress.Parsing -> "Parsing... ${(sync as SyncService.Progress.Parsing).parsed} entries"
                        SyncService.Progress.PostProcessing -> "Writing to library..."
                        is SyncService.Progress.Error -> "Failed: ${(sync as SyncService.Progress.Error).message}"
                        is SyncService.Progress.Done -> "Done, ${(sync as SyncService.Progress.Done).totalParsed} items"
                        SyncService.Progress.Idle -> "Re-download and parse the M3U playlist"
                    },
                    accent = !isSyncing,
                    onClick = { if (!isSyncing) viewModel.refreshLibrary() },
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        // Region whitelist (countries).
        FilterSection(
            title = "Region",
            description = "Only show channels from these regions. Empty means everywhere.",
            items = AppSettings.KNOWN_COUNTRIES,
            selected = settings.countryWhitelist,
            onToggle = { code ->
                val current = settings.countryWhitelist
                viewModel.setCountryWhitelist(
                    if (code in current) current - code else current + code,
                )
            },
            onSelectAll = {
                viewModel.setCountryWhitelist(AppSettings.KNOWN_COUNTRIES.map { it.first }.toSet())
            },
            onDeselectAll = { viewModel.setCountryWhitelist(emptySet()) },
        )

        Spacer(Modifier.height(20.dp))

        // Excluded categories.  Default is XXX/Adult/Religious.
        // We show the defaults plus anything the user has manually
        // added — so they can untick and they can't currently add
        // arbitrary new keywords from this screen.  Custom keywords
        // can be added via a future "Add..." chip.
        val excludedItems: List<Pair<String, String>> = remember(settings.excludedCategories) {
            (AppSettings.DEFAULT_EXCLUDED_CATEGORIES + settings.excludedCategories)
                .map { it to it }
                .distinctBy { it.first }
        }
        FilterSection(
            title = "Excluded categories",
            description = "Drop channels and content whose group title matches these keywords.",
            items = excludedItems,
            selected = settings.excludedCategories,
            onToggle = { code ->
                val current = settings.excludedCategories
                viewModel.setExcludedCategories(
                    if (code in current) current - code else current + code,
                )
            },
            onSelectAll = {
                viewModel.setExcludedCategories(excludedItems.map { it.first }.toSet())
            },
            onDeselectAll = { viewModel.setExcludedCategories(emptySet()) },
        )

        Spacer(Modifier.height(20.dp))

        // Wanted languages (whitelist for movies + series).
        FilterSection(
            title = "Languages",
            description = "Only enrich and surface movies + series in these languages.",
            items = AppSettings.KNOWN_LANGUAGES,
            selected = settings.wantedLanguages,
            onToggle = { code ->
                val current = settings.wantedLanguages
                viewModel.setWantedLanguages(
                    if (code in current) current - code else current + code,
                )
            },
            onSelectAll = {
                viewModel.setWantedLanguages(AppSettings.KNOWN_LANGUAGES.map { it.first }.toSet())
            },
            onDeselectAll = { viewModel.setWantedLanguages(emptySet()) },
        )

        Spacer(Modifier.height(20.dp))

        SectionHeader("Minimum year")
        Card {
            Column {
                ListRow(
                    headline = "Hide films + shows older than",
                    supporting = when (settings.minimumYear) {
                        0 -> "Showing every year"
                        else -> "Currently ${settings.minimumYear} and newer"
                    },
                )
                // Show each option as its own list row so a D-pad press
                // immediately swaps the value (no dropdown overlay).
                AppSettings.MINIMUM_YEAR_OPTIONS.forEach { year ->
                    val label = if (year == 0) "No minimum" else year.toString()
                    ListRow(
                        headline = label,
                        selected = year == settings.minimumYear,
                        onClick = { viewModel.setMinimumYear(year) },
                    )
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        SectionHeader("Playback")
        Card {
            ListRow(
                headline = "Stop stream when browsing menus",
                supporting = if (settings.stopStreamInMenus) "On, saves bandwidth"
                else "Off, preview keeps playing",
                onClick = { viewModel.setStopStreamInMenus(!settings.stopStreamInMenus) },
                accent = settings.stopStreamInMenus,
            )
        }

        Spacer(Modifier.height(20.dp))

        SectionHeader("About")
        Card {
            Column {
                ListRow(
                    headline = "Strata TV",
                    supporting = "v0.1.2",
                )
                ListRow(
                    headline = "EPG source",
                    supporting = settings.provider.toEpgUrl()?.let { maskUrl(it) }
                        ?: "Not available for this provider",
                )
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

// =====================================================================
// Filter section: checkbox grid + select-all / deselect-all
// =====================================================================

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun FilterSection(
    title: String,
    description: String,
    items: List<Pair<String, String>>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    onSelectAll: () -> Unit,
    onDeselectAll: () -> Unit,
) {
    SectionHeader(title)
    Card {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = description,
                color = StrataColors.TextSecondary,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "${selected.intersect(items.map { it.first }.toSet()).size} of ${items.size} selected",
                color = StrataColors.TextTertiary,
                fontSize = 11.sp,
            )
            Spacer(Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MiniButton(label = "Select all", onClick = onSelectAll)
                MiniButton(label = "Deselect all", onClick = onDeselectAll)
            }

            Spacer(Modifier.height(12.dp))

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                items.forEach { (code, displayName) ->
                    val isOn = code in selected
                    Surface(
                        onClick = { onToggle(code) },
                        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(20.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = if (isOn) StrataColors.AccentPrimary.copy(alpha = 0.85f)
                            else StrataColors.SurfaceFloat,
                            focusedContainerColor = if (isOn) StrataColors.AccentPrimaryBright
                            else StrataColors.SurfaceOverlay,
                        ),
                    ) {
                        // Keep the label identical whether on/off so the
                        // chip doesn't resize when toggled.  The colour
                        // shift is the only state signal needed.
                        Text(
                            text = displayName,
                            color = if (isOn) Color.White else StrataColors.TextPrimary,
                            fontSize = 13.sp,
                            fontWeight = if (isOn) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun MiniButton(label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(6.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = StrataColors.SurfaceRaised,
            focusedContainerColor = StrataColors.AccentPrimary.copy(alpha = 0.6f),
        ),
    ) {
        Text(
            text = label,
            color = StrataColors.TextSecondary,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

// =====================================================================
// Card + rows (used by simple sections)
// =====================================================================

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun Card(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(StrataColors.SurfaceRaised),
    ) { content() }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ListRow(
    headline: String,
    supporting: String? = null,
    accent: Boolean = false,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    Surface(
        onClick = { onClick?.invoke() },
        enabled = onClick != null,
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = StrataColors.SurfaceRaised,
            focusedContainerColor = StrataColors.SurfaceFloat,
            disabledContainerColor = StrataColors.SurfaceRaised,
        ),
        // Disable the default 1.05x focus zoom so the row's left edge
        // doesn't get pushed past the parent's left padding into the
        // sidebar area.  Focus is still signalled via the colour
        // change above.
        scale = ClickableSurfaceDefaults.scale(
            scale = 1f,
            focusedScale = 1f,
            pressedScale = 1f,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
            // No text mutation on selection (would resize the row).
            // Selection state shows via colour + weight instead.
            Text(
                text = headline,
                color = when {
                    accent -> StrataColors.AccentPrimary
                    selected -> StrataColors.AccentPrimaryBright
                    else -> StrataColors.TextPrimary
                },
                fontSize = 15.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
            if (supporting != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = supporting,
                    color = StrataColors.TextSecondary,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title.uppercase(),
        color = StrataColors.TextSecondary,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.5.sp,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

private fun maskUrl(url: String): String =
    try {
        val uri = java.net.URI(url)
        "${uri.scheme}://${uri.host}/****"
    } catch (_: Exception) {
        "****"
    }

private fun describeProvider(provider: ProviderConfig): String = when {
    !provider.isConfigured -> "Not configured, run setup wizard"
    provider.providerId == "custom_m3u" -> maskUrl(provider.customM3uUrl)
    else -> "${provider.providerId} | ${provider.username}"
}

private fun formatExpiry(instant: Instant): String =
    DateTimeFormatter.ofPattern("d MMM yyyy")
        .withZone(ZoneId.systemDefault())
        .format(instant)

private fun SyncFrequency.displayName(): String = when (this) {
    SyncFrequency.EveryLaunch -> "Every launch"
    SyncFrequency.Daily -> "Daily"
    SyncFrequency.EveryTwoDays -> "Every 2 days"
}

private fun SyncFrequency.description(): String = when (this) {
    SyncFrequency.EveryLaunch -> "Sync the library every time you open the app"
    SyncFrequency.Daily -> "First open each calendar day triggers a sync"
    SyncFrequency.EveryTwoDays -> "Sync once every two calendar days"
}
