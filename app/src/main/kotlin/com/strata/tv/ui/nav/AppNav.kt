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
 * The app has six top-level destinations (see [Destination]) and a
 * tiny number of detail routes (series detail, player) — far short of
 * needing Compose Navigation 3, which would add ceremony for very
 * little gain.  A bare [AppNavState] holding the current destination
 * and a back stack is enough.
 *
 * Holds two [FocusRequester]s used by the shell to bridge focus
 * between the sidebar and the content area:
 *
 * - [sidebarRequester] — used to send focus into the sidebar (Back
 *   button, app launch with sidebar autofocus).
 * - [contentRequester] — used to send focus into the content area
 *   (D-pad Right from the sidebar, or Back button when sidebar
 *   already has focus).
 *
 * Each screen that renders into the content area attaches the
 * `contentRequester` to its first focusable widget so the cross-zone
 * jump always lands somewhere visible.
 */
@Stable
class AppNavState internal constructor(
    initialDestination: Destination,
) {
    var current: Destination by mutableStateOf(initialDestination)
        private set

    /**
     * When non-null, [Shell] renders the player screen full-bleed on
     * top of whatever tab was selected.  Closing the player (Back)
     * clears this back to null and reveals the original tab.
     */
    var playerArgs: PlayerArgs? by mutableStateOf(null)
        private set

    val sidebarRequester: FocusRequester = FocusRequester()
    val contentRequester: FocusRequester = FocusRequester()

    fun navigate(destination: Destination) {
        current = destination
    }

    fun openPlayer(args: PlayerArgs) {
        playerArgs = args
    }

    fun closePlayer() {
        playerArgs = null
    }
}

/**
 * Args plumbed from a "play" tap on any card to the player screen.
 * Plain data class — no Compose dependencies — so it can be passed
 * around freely (Movies / Live / Search / Home all build one of
 * these from their own row type and call [AppNavState.openPlayer]).
 */
@Stable
data class PlayerArgs(
    val streamUrl: String,
    val title: String,
    val isLive: Boolean,
    val resumePositionMs: Long = 0,
    val contentType: String = "movie",
    val artworkUrl: String = "",
)

/** Composable factory — the state outlives configuration changes via [remember]. */
@Composable
fun rememberAppNavState(
    initialDestination: Destination = Destination.Home,
): AppNavState = remember { AppNavState(initialDestination) }
