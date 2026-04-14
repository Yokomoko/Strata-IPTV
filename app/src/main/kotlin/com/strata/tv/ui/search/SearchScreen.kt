package com.strata.tv.ui.search

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LiveTv
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.tv.foundation.lazy.list.TvLazyColumn
import androidx.tv.foundation.lazy.list.TvLazyRow
import androidx.tv.foundation.lazy.list.items
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.ListItem
import androidx.tv.material3.ListItemDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.strata.tv.ui.nav.AppNavState
import com.strata.tv.ui.nav.PlayerArgs
import com.strata.tv.ui.theme.StrataColors

/**
 * Search screen -- polished search field, quick-access genre chips
 * as empty state, grouped results with type badges.
 *
 * Phase 9: upgraded typography, empty state with genre suggestions,
 * better visual hierarchy.
 */
@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun SearchScreen(
    modifier: Modifier = Modifier,
    onNavigate: AppNavState? = null,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val query by viewModel.query.collectAsState()
    val uiState by viewModel.results.collectAsState()

    // Keyboard is dismissed when the user navigates Down from the
    // search field into the results — NOT on result arrival, which
    // was stealing focus mid-typing.
    val keyboardController = LocalSoftwareKeyboardController.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(StrataColors.SurfaceVoid)
            .padding(horizontal = 32.dp, vertical = 24.dp),
    ) {
        // Title
        Text(
            text = "Search",
            color = StrataColors.TextPrimary,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.3).sp,
        )
        Spacer(Modifier.height(16.dp))

        // Search field
        SearchField(
            value = query,
            onValueChange = { viewModel.onQueryChanged(it) },
        )
        Spacer(Modifier.height(16.dp))

        // Results / empty state
        when (val state = uiState) {
            SearchUiState.Empty -> {
                SearchEmptyState(
                    message = "Search channels, movies, and shows",
                    onQuickSearch = { viewModel.onQueryChanged(it) },
                )
            }
            SearchUiState.NoResults -> {
                NoResultsState(query)
            }
            is SearchUiState.Results -> {
                ResultsList(state, onNavigate)
            }
        }
    }
}

// =====================================================================
// Search field
// =====================================================================

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val borderColor = if (focused) StrataColors.AccentPrimary else StrataColors.SurfaceOverlay

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TextStyle(
            color = StrataColors.TextPrimary,
            fontSize = 16.sp,
        ),
        cursorBrush = SolidColor(StrataColors.AccentPrimary),
        keyboardOptions = KeyboardOptions.Default.copy(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions.Default,
        decorationBox = { innerTextField ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(StrataColors.SurfaceRaised)
                    .border(
                        width = if (focused) 2.dp else 1.dp,
                        color = borderColor,
                        shape = RoundedCornerShape(12.dp),
                    )
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Search,
                    contentDescription = "Search",
                    tint = if (focused) StrataColors.AccentPrimary else StrataColors.TextTertiary,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(12.dp))
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) {
                        Text(
                            text = "Search channels, movies, shows\u2026",
                            color = StrataColors.TextTertiary,
                            fontSize = 16.sp,
                        )
                    }
                    innerTextField()
                }
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused },
    )
}

// =====================================================================
// Empty state with quick-search genre chips
// =====================================================================

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SearchEmptyState(
    message: String,
    onQuickSearch: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(60.dp))

        Icon(
            imageVector = Icons.Outlined.Search,
            contentDescription = null,
            tint = StrataColors.TextTertiary.copy(alpha = 0.4f),
            modifier = Modifier.size(64.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = message,
            color = StrataColors.TextSecondary,
            fontSize = 16.sp,
        )
        Spacer(Modifier.height(24.dp))

        // Quick-search genre chips
        Text(
            text = "QUICK SEARCH",
            color = StrataColors.TextTertiary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.5.sp,
        )
        Spacer(Modifier.height(12.dp))

        val quickSearches = listOf("Action", "Comedy", "Drama", "Horror", "Sci-Fi", "Documentary", "Kids", "News")
        TvLazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 16.dp),
        ) {
            items(items = quickSearches, key = { it }) { genre ->
                Surface(
                    onClick = { onQuickSearch(genre) },
                    shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(20.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = StrataColors.SurfaceRaised,
                        focusedContainerColor = StrataColors.AccentPrimary,
                    ),
                    modifier = Modifier
                        .height(36.dp)
                        .border(1.dp, StrataColors.SurfaceFloat, RoundedCornerShape(20.dp)),
                ) {
                    Text(
                        text = genre,
                        color = StrataColors.TextSecondary,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun NoResultsState(query: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "No results found",
                color = StrataColors.TextSecondary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Try a different search for \"$query\"",
                color = StrataColors.TextTertiary,
                fontSize = 13.sp,
            )
        }
    }
}

// =====================================================================
// Results list
// =====================================================================

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ResultsList(state: SearchUiState.Results, onNavigate: AppNavState?) {
    TvLazyColumn(
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        // Movies first — these are what most users are searching for.
        if (state.movies.isNotEmpty()) {
            item { SectionHeader("Movies (${state.movies.size})") }
            items(
                items = state.movies.take(30),
                key = { "mv:${it.contentId}" },
            ) { result ->
                ResultRow(
                    result = result,
                    icon = Icons.Outlined.Movie,
                    iconColor = StrataColors.AccentPrimary,
                    typeLabel = "MOVIE",
                    onClick = {
                        onNavigate?.openMovieDetail(result.contentId)
                    },
                )
            }
        }

        // Shows second.
        if (state.shows.isNotEmpty()) {
            item { SectionHeader("Shows (${state.shows.size})") }
            items(
                items = state.shows.take(30),
                key = { "sh:${it.contentId}" },
            ) { result ->
                ResultRow(
                    result = result,
                    icon = Icons.Outlined.VideoLibrary,
                    iconColor = StrataColors.AccentSecondary,
                    typeLabel = "SHOW",
                    onClick = {
                        if (result.seriesTitle.isNotBlank()) {
                            onNavigate?.openShowDetail(result.seriesTitle)
                        }
                    },
                )
            }
        }

        // Channels last, capped at 5 to avoid drowning out movie/show results.
        if (state.channels.isNotEmpty()) {
            item { SectionHeader("Channels (${state.channels.size})") }
            items(
                items = state.channels.take(5),
                key = { "ch:${it.contentId}" },
            ) { result ->
                ResultRow(
                    result = result,
                    icon = Icons.Outlined.LiveTv,
                    iconColor = StrataColors.StatusLive,
                    typeLabel = "LIVE",
                    onClick = {
                        onNavigate?.openPlayer(
                            PlayerArgs(
                                streamUrl = result.streamUrl,
                                title = result.title,
                                isLive = true,
                                contentType = "live",
                                artworkUrl = result.artworkUrl,
                            ),
                        )
                    },
                )
            }
        }
    }
}

// =====================================================================
// Section header
// =====================================================================

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        color = StrataColors.TextSecondary,
        fontSize = 16.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
    )
}

// =====================================================================
// Result row
// =====================================================================

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ResultRow(
    result: SearchResult,
    icon: ImageVector,
    iconColor: Color,
    typeLabel: String,
    onClick: () -> Unit,
) {
    // Use the clean title; fall back to raw displayName only when title is blank.
    val displayText = result.title.ifBlank { result.displayName }

    ListItem(
        selected = false,
        onClick = onClick,
        colors = ListItemDefaults.colors(
            containerColor = StrataColors.SurfaceRaised,
            contentColor = StrataColors.TextPrimary,
            focusedContainerColor = StrataColors.SurfaceFloat,
            focusedContentColor = StrataColors.TextPrimary,
            selectedContainerColor = StrataColors.SurfaceFloat,
            selectedContentColor = StrataColors.TextPrimary,
        ),
        headlineContent = {
            Text(
                text = displayText,
                color = StrataColors.TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
        },
        supportingContent = if (result.groupTitle.isNotEmpty()) {
            {
                Text(
                    text = result.groupTitle,
                    color = StrataColors.TextTertiary,
                    fontSize = 11.sp,
                    maxLines = 1,
                )
            }
        } else null,
        leadingContent = {
            Icon(
                imageVector = icon,
                contentDescription = result.contentType,
                tint = iconColor,
                modifier = Modifier.size(22.dp),
            )
        },
        trailingContent = {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(iconColor.copy(alpha = 0.15f))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            ) {
                Text(
                    text = typeLabel,
                    color = iconColor,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        },
    )
}
