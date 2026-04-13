package com.strata.tv.ui.movies

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.foundation.lazy.grid.TvGridCells
import androidx.tv.foundation.lazy.grid.TvLazyVerticalGrid
import androidx.tv.foundation.lazy.grid.items
import androidx.tv.foundation.lazy.list.TvLazyRow
import androidx.tv.foundation.lazy.list.items
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.strata.tv.ui.theme.StrataColors
import com.strata.tv.ui.widgets.PosterCard

/**
 * Movies grid screen — displays all visible movies as poster cards in
 * a vertical grid, with a genre chip row along the top for filtering.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun MoviesScreen(
    modifier: Modifier = Modifier,
    viewModel: MoviesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(StrataColors.SurfaceVoid),
    ) {
        // -- Screen title --
        Text(
            text = "Movies",
            color = StrataColors.TextPrimary,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 32.dp, top = 24.dp, bottom = 12.dp),
        )

        // -- Genre chip row --
        TvLazyRow(
            contentPadding = PaddingValues(horizontal = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(state.genres) { genre ->
                GenreChip(
                    label = genre,
                    selected = genre == state.selectedGenre,
                    onClick = { viewModel.selectGenre(genre) },
                )
            }
        }

        // -- Poster grid --
        if (state.filteredMovies.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No movies found",
                    color = StrataColors.TextTertiary,
                    fontSize = 14.sp,
                )
            }
        } else {
            TvLazyVerticalGrid(
                columns = TvGridCells.Adaptive(minSize = 180.dp),
                contentPadding = PaddingValues(
                    start = 32.dp,
                    end = 32.dp,
                    top = 16.dp,
                    bottom = 32.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(
                    items = state.filteredMovies,
                    key = { it.contentId },
                ) { movie ->
                    PosterCard(
                        title = movie.movieTitle,
                        subtitle = movie.year?.toString(),
                        posterUrl = movie.posterUrl.takeIf { it.isNotBlank() },
                        onClick = { /* TODO Phase 4 player */ },
                        cardSize = DpSize(180.dp, 320.dp),
                    )
                }
            }
        }
    }
}

/**
 * Genre filter chip — a small clickable card that toggles genre
 * selection.  Selected state uses the accent colour; idle is a
 * neutral raised surface.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun GenreChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        shape = CardDefaults.shape(shape = RoundedCornerShape(16.dp)),
        colors = CardDefaults.colors(
            containerColor = if (selected) StrataColors.AccentPrimary
            else StrataColors.SurfaceRaised,
        ),
    ) {
        Text(
            text = label,
            color = if (selected) StrataColors.TextPrimary
            else StrataColors.TextSecondary,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}
