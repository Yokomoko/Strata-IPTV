package com.strata.tv.ui.movies

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.strata.tv.data.db.MovieDao
import com.strata.tv.data.db.MovieListItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Drives [MoviesScreen].
 *
 * Uses the lightweight [MovieListItem] projection instead of the full
 * [MovieEntity] to avoid CursorWindow overflow on large libraries
 * (2000+ movies with long overview/cast text would exceed the 2 MB
 * window).  Detail screens load the full entity by contentId.
 */
@HiltViewModel
class MoviesViewModel @Inject constructor(
    private val movieDao: MovieDao,
) : ViewModel() {

    val state: StateFlow<MoviesUiState> = movieDao.watchAllForList()
        .map { allMovies ->
            val hero = allMovies.firstOrNull { it.posterUrl.isNotBlank() }
            val genreRails = buildGenreRails(allMovies)
            MoviesUiState(
                hero = hero,
                allMovies = allMovies,
                genreRails = genreRails,
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = MoviesUiState.Empty,
        )

    private fun buildGenreRails(movies: List<MovieListItem>): List<GenreRail> {
        val byGenre = mutableMapOf<String, MutableList<MovieListItem>>()
        for (movie in movies) {
            val genres = movie.genre.split(",", "|", "/")
                .map { it.trim() }
                .filter { it.isNotBlank() }
            if (genres.isEmpty()) {
                byGenre.getOrPut("Uncategorised") { mutableListOf() }.add(movie)
            } else {
                val primary = genres.first()
                byGenre.getOrPut(primary) { mutableListOf() }.add(movie)
            }
        }
        return byGenre.entries
            .filter { it.value.size >= 3 }
            .sortedByDescending { it.value.size }
            .take(15)
            .map { (genre, items) -> GenreRail(genre, items) }
    }
}

data class GenreRail(
    val genre: String,
    val movies: List<MovieListItem>,
)

data class MoviesUiState(
    val hero: MovieListItem?,
    val allMovies: List<MovieListItem>,
    val genreRails: List<GenreRail>,
) {
    companion object {
        val Empty = MoviesUiState(
            hero = null,
            allMovies = emptyList(),
            genreRails = emptyList(),
        )
    }
}
