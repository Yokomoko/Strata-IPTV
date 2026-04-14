package com.strata.tv.ui.movies

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.strata.tv.data.db.ContentDao
import com.strata.tv.data.db.MovieDao
import com.strata.tv.data.db.MovieEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Drives [MoviesScreen].
 *
 * Streams all visible movies grouped by genre for the rail layout,
 * plus a "featured" hero item (the newest movie with a poster).
 */
@HiltViewModel
class MoviesViewModel @Inject constructor(
    private val movieDao: MovieDao,
) : ViewModel() {

    val state: StateFlow<MoviesUiState> = movieDao.watchAllByYear()
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

    private fun buildGenreRails(movies: List<MovieEntity>): List<GenreRail> {
        val byGenre = mutableMapOf<String, MutableList<MovieEntity>>()
        for (movie in movies) {
            val genres = movie.genre.split(",", "|", "/")
                .map { it.trim() }
                .filter { it.isNotBlank() }
            if (genres.isEmpty()) {
                byGenre.getOrPut("Uncategorised") { mutableListOf() }.add(movie)
            } else {
                // Add to first genre only to avoid duplication across rails
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
    val movies: List<MovieEntity>,
)

data class MoviesUiState(
    val hero: MovieEntity?,
    val allMovies: List<MovieEntity>,
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
