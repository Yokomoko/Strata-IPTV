package com.strata.tv.ui.movies

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.strata.tv.data.db.MovieDao
import com.strata.tv.data.db.MovieListItem
import com.strata.tv.domain.GenreGrouper
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
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
 *
 * Genre rails are built on a separate Flow (with Dispatchers.Default
 * + distinctUntilChanged on the genre signature) so enrichment writes
 * don't re-run the expensive grouping pass on every Room invalidation
 * and block recomposition mid-scroll.
 */
@HiltViewModel
class MoviesViewModel @Inject constructor(
    private val movieDao: MovieDao,
) : ViewModel() {

    /** Hot list flow — small map, cheap, stays on the Room dispatcher. */
    private val movies = movieDao.watchAllForList()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    /**
     * Genre rails flow — only re-builds when the genre signature changes
     * (list of (contentId, genre) pairs).  Title/poster updates from
     * enrichment don't affect the genre column so they no longer
     * trigger a full regroup.  Grouping runs on Default dispatcher.
     */
    private val genreRails = movies
        .map { list -> list.map { it.contentId to it.genre } }
        .distinctUntilChanged()
        .map { GenreGrouper.group(movies.value) }
        .flowOn(Dispatchers.Default)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    val state: StateFlow<MoviesUiState> = combine(movies, genreRails) { list, rails ->
        MoviesUiState(
            hero = list.firstOrNull { it.posterUrl.isNotBlank() },
            allMovies = list,
            genreRails = rails,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MoviesUiState.Empty,
    )
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
