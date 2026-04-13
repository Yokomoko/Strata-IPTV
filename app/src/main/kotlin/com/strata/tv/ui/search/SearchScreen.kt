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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
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
import androidx.tv.foundation.lazy.list.items
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.ListItem
import androidx.tv.material3.Text
import com.strata.tv.ui.theme.StrataColors

/**
 * Global search screen — text field at the top, grouped results below.
 *
 * Ported from v1's `search_screen.dart` but using native Compose for TV
 * components ([ListItem], [TvLazyColumn]) for a polished leanback feel.
 *
 * Voice search hooks (Phase 8) will plug into [onVoiceQuery] — a lambda
 * that feeds recognised text into the ViewModel the same way the
 * keyboard does.  The mic button is not rendered yet.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SearchScreen(
    modifier: Modifier = Modifier,
    viewModel: SearchViewModel = hiltViewModel(),
    onResultClick: (SearchResult) -> Unit = {},
) {
    val query by viewModel.query.collectAsState()
    val uiState by viewModel.results.collectAsState()

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
                EmptyHint("Type at least 2 characters to search")
            }
            SearchUiState.NoResults -> {
                EmptyHint("No results for \"$query\"")
            }
            is SearchUiState.Results -> {
                ResultsList(state, onResultClick)
            }
        }
    }
}

// =====================================================================
// Search field — TV-friendly BasicTextField with custom decoration
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
                    .border(1.dp, borderColor, RoundedCornerShape(12.dp))
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Search,
                    contentDescription = "Search",
                    tint = StrataColors.TextTertiary,
                    modifier = Modifier.size(20.dp),
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
// Results list — vertical TvLazyColumn with grouped sections
// =====================================================================

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ResultsList(
    state: SearchUiState.Results,
    onResultClick: (SearchResult) -> Unit,
) {
    TvLazyColumn(
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (state.channels.isNotEmpty()) {
            item { SectionHeader("Channels (${state.channels.size})") }
            items(
                items = state.channels.take(20),
                key = { "ch:${it.contentId}" },
            ) { result ->
                ResultRow(result, Icons.Outlined.LiveTv, StrataColors.StatusLive, "LIVE") {
                    onResultClick(result)
                }
            }
        }

        if (state.movies.isNotEmpty()) {
            item { SectionHeader("Movies (${state.movies.size})") }
            items(
                items = state.movies.take(30),
                key = { "mv:${it.contentId}" },
            ) { result ->
                ResultRow(result, Icons.Outlined.Movie, StrataColors.AccentPrimary, "MOVIE") {
                    onResultClick(result)
                }
            }
        }

        if (state.shows.isNotEmpty()) {
            item { SectionHeader("Shows (${state.shows.size})") }
            items(
                items = state.shows.take(30),
                key = { "sh:${it.contentId}" },
            ) { result ->
                ResultRow(result, Icons.Outlined.VideoLibrary, StrataColors.AccentSecondary, "SHOW") {
                    onResultClick(result)
                }
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
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
    )
}

// =====================================================================
// Result row — TV ListItem
// =====================================================================

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ResultRow(
    result: SearchResult,
    icon: ImageVector,
    iconColor: androidx.compose.ui.graphics.Color,
    typeLabel: String,
    onClick: () -> Unit,
) {
    ListItem(
        selected = false,
        onClick = onClick,
        headlineContent = {
            Text(
                text = result.title,
                color = StrataColors.TextPrimary,
                fontSize = 14.sp,
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
                modifier = Modifier.size(20.dp),
            )
        },
        trailingContent = {
            Text(
                text = typeLabel,
                color = iconColor,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
            )
        },
    )
}

// =====================================================================
// Empty / hint state
// =====================================================================

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun EmptyHint(message: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = message,
            color = StrataColors.TextTertiary,
            fontSize = 14.sp,
        )
    }
}
