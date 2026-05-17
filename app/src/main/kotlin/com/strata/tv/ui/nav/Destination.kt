package com.strata.tv.ui.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LiveTv
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * The set of top-level destinations rendered in the sidebar.
 *
 * Order matters — it's the order the rail items appear on screen and
 * the index used by [SidebarFocus] to track which item the user is on.
 *
 * The rounded variant of each icon is used when selected (matches the
 * Sky / Material 3 convention of "filled when active, outlined when
 * idle").
 */
enum class Destination(
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
) {
    Home("Home", Icons.Outlined.Home, Icons.Rounded.Home),
    Live("TV Guide", Icons.Outlined.LiveTv, Icons.Rounded.LiveTv),
    Movies("Movies", Icons.Outlined.Movie, Icons.Rounded.Movie),
    Shows("Box Sets", Icons.Outlined.VideoLibrary, Icons.Rounded.VideoLibrary),
    Search("Search", Icons.Outlined.Search, Icons.Rounded.Search),
    Settings("Settings", Icons.Outlined.Settings, Icons.Rounded.Settings),
}
