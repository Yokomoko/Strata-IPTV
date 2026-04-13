package com.strata.tv.ui.nav

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import com.strata.tv.ui.home.HomeScreen
import com.strata.tv.ui.theme.StrataColors

/**
 * Top-level shell — renders the persistent sidebar on the left and
 * the currently-selected destination's screen on the right.
 *
 * Focus model:
 * - On launch, the sidebar autofocuses (LaunchedEffect on first frame).
 * - D-pad Right from the sidebar enters the content area via the
 *   sidebar's exit-direction focus property.
 * - The back button toggles focus between sidebar and content
 *   without popping the navigator — same UX rule v1 ended up with
 *   after a lot of iteration.  See `lib/app/shell_scaffold.dart`
 *   in the Flutter app for the full back-handler logic.
 */
@Composable
fun Shell(
    nav: AppNavState = rememberAppNavState(),
) {
    // Track which zone last had focus so the back-button toggle
    // knows whether to send focus to sidebar or content.
    var sidebarHasFocus by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        // First-frame autofocus on the sidebar — sets the user's
        // entry point on app launch.
        runCatching { nav.sidebarRequester.requestFocus() }
    }

    BackHandler {
        if (sidebarHasFocus) {
            // Sidebar has focus → push focus into the content area.
            // (Same gesture as Fire Stick remote's Back when the
            // user is parked on the sidebar — they want to keep
            // watching what's shown, not exit.)
            runCatching { nav.contentRequester.requestFocus() }
        } else {
            // Content has focus → bounce back to sidebar.
            runCatching { nav.sidebarRequester.requestFocus() }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(StrataColors.SurfaceVoid),
    ) {
        Sidebar(
            selected = nav.current,
            onSelected = { nav.navigate(it) },
            sidebarFocusRequester = nav.sidebarRequester,
            modifier = Modifier.onFocusChanged { sidebarHasFocus = it.hasFocus },
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(nav.contentRequester)
                .onFocusChanged { if (it.hasFocus) sidebarHasFocus = false },
        ) {
            when (nav.current) {
                Destination.Home -> HomeScreen()
                Destination.Live -> Placeholder("TV Guide")
                Destination.Movies -> Placeholder("Movies")
                Destination.Shows -> Placeholder("Box Sets")
                Destination.Search -> Placeholder("Search")
                Destination.Settings -> Placeholder("Settings")
            }
        }
    }
}

/** Temporary placeholder for screens not yet built. */
@Composable
private fun Placeholder(label: String) {
    androidx.compose.foundation.layout.Column(
        modifier = Modifier
            .fillMaxSize()
            .background(StrataColors.SurfaceVoid),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
    ) {
        androidx.tv.material3.Text(
            text = label,
            color = StrataColors.TextSecondary,
            fontSize = androidx.compose.ui.unit.TextUnit.Unspecified,
        )
        androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp).width(8.dp))
        androidx.tv.material3.Text(
            text = "Not built yet — coming in a later phase.",
            color = StrataColors.TextTertiary,
        )
    }
}
