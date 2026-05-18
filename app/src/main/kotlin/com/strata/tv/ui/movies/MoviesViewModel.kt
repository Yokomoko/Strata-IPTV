package com.strata.tv.ui.movies

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.strata.tv.data.db.MovieDao
import com.strata.tv.data.db.MovieListItem
import com.strata.tv.data.db.WatchlistDao
import com.strata.tv.data.db.WatchlistEntity
import com.strata.tv.domain.GenreGrouper
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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
    private val watchlistDao: WatchlistDao,
    private val libraryFilter: com.strata.tv.data.repo.LibraryFilterRepository,
) : ViewModel() {

    /** Hot list flow — small map, cheap, stays on the Room dispatcher. */
    private val movies = movieDao.watchAllForList()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    /**
     * Genre rails flow — only re-builds when the genre signature
     * changes.  Uses a sorted (contentId, genre) key so reorder-only
     * changes from the upstream query don't force a regroup, and maps
     * the emitted list directly so the grouping always matches the
     * triggering snapshot.  Grouping runs on Default dispatcher.
     */
    private val genreRails = movies
        .distinctUntilChangedBy { list ->
            list.map { it.contentId to it.genre }.sortedBy { it.first }
        }
        .map { list -> GenreGrouper.group(list) }
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

    // -- Watchlist --------------------------------------------------------

    val watchlistIds: StateFlow<Set<String>> = watchlistDao.watchAll()
        .map { list -> list.map { it.contentId }.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    fun addToWatchlist(contentId: String, title: String, artworkUrl: String) {
        viewModelScope.launch {
            watchlistDao.add(
                WatchlistEntity(
                    contentId = contentId,
                    contentType = "movie",
                    title = title,
                    artworkUrl = artworkUrl,
                ),
            )
        }
    }

    fun removeFromWatchlist(contentId: String) {
        viewModelScope.launch { watchlistDao.remove(contentId) }
    }

    fun hideMovie(contentId: String) {
        viewModelScope.launch { libraryFilter.hideMovie(contentId) }
    }

    fun ignoreGenre(genre: String) {
        viewModelScope.launch { libraryFilter.ignoreGenre(genre) }
    }

    fun ignoreLanguage(languageCode: String) {
        viewModelScope.launch { libraryFilter.ignoreLanguage(languageCode) }
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
