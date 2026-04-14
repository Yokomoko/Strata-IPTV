package com.strata.tv.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester

/**
 * Lightweight in-memory nav state for Strata TV.
 *
 * Phase 9: added [DetailRoute] for movie / show detail screens,
 * plus [PlayerArgs] for launching the player from detail pages.
 * The detail screen sits *on top* of the current destination
 * (like a dialog / overlay) so the back button returns to the
 * rail the user was browsing.
 */
@Stable
class AppNavState internal constructor(
    initialDestination: Destination,
) {
    var current: Destination by mutableStateOf(initialDestination)
        private set

    /** Detail overlay -- non-null when a movie/show detail screen is visible. */
    var detailRoute: DetailRoute? by mutableStateOf(null)
        private set

    /** Player args -- non-null when the player should be shown. */
    var playerArgs: PlayerArgs? by mutableStateOf(null)
        private set

    val sidebarRequester: FocusRequester = FocusRequester()
    val contentRequester: FocusRequester = FocusRequester()

    fun navigate(destination: Destination) {
        detailRoute = null
        playerArgs = null
        current = destination
    }

    fun openMovieDetail(contentId: String) {
        detailRoute = DetailRoute.Movie(contentId)
    }

    fun openShowDetail(seriesTitle: String) {
        detailRoute = DetailRoute.Show(seriesTitle)
    }

    fun closeDetail() {
        detailRoute = null
    }

    fun openPlayer(args: PlayerArgs) {
        playerArgs = args
    }

    fun closePlayer() {
        playerArgs = null
    }

    /** Navigate back from the deepest overlay first. */
    fun navigateBack(): Boolean {
        return when {
            playerArgs != null -> { playerArgs = null; true }
            detailRoute != null -> { detailRoute = null; true }
            else -> false
        }
    }
}

/** Detail screen routing. */
sealed interface DetailRoute {
    data class Movie(val contentId: String) : DetailRoute
    data class Show(val seriesTitle: String) : DetailRoute
}

/**
 * Lightweight info for a single channel in a live channel list.
 * Passed via [PlayerArgs] so the player can switch channels
 * without returning to the guide.
 */
data class ChannelPlayInfo(
    val contentId: String,
    val streamUrl: String,
    val displayName: String,
    val logoUrl: String,
    val nowTitle: String?,
    val nextTitle: String? = null,
)

/** Arguments for launching the player. */
data class PlayerArgs(
    val streamUrl: String,
    val title: String,
    val isLive: Boolean = false,
    val resumePositionMs: Long = 0L,
    val contentType: String = "movie",
    val artworkUrl: String = "",
    /** For live playback: the full filtered channel list for D-pad switching. */
    val channelList: List<ChannelPlayInfo> = emptyList(),
    /** Index of the currently-playing channel within [channelList]. */
    val currentIndex: Int = 0,
    /** Series context for next-episode autoplay (shows only). */
    val seriesTitle: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
)

/** Composable factory. */
@Composable
fun rememberAppNavState(
    initialDestination: Destination = Destination.Home,
): AppNavState = remember { AppNavState(initialDestination) }
