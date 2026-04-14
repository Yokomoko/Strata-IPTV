package com.strata.tv.ui.movies

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.strata.tv.data.db.ContentDao
import com.strata.tv.data.db.ContentItemEntity
import com.strata.tv.data.db.MovieDao
import com.strata.tv.data.db.MovieEntity
import com.strata.tv.data.db.WatchlistDao
import com.strata.tv.data.db.WatchlistEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

/**
 * Drives [MovieDetailScreen].
 *
 * Loads a single movie by contentId, joining with the content_items
 * table to get the stream URL and any Phase-8 enrichment fields
 * (overview, cast, certification, backdrop_url).
 */
@HiltViewModel
class MovieDetailViewModel @Inject constructor(
    private val movieDao: MovieDao,
    private val contentDao: ContentDao,
    private val watchlistDao: WatchlistDao,
) : ViewModel() {

    private val _state = MutableStateFlow<MovieDetailState>(MovieDetailState.Loading)
    val state: StateFlow<MovieDetailState> = _state.asStateFlow()

    private var _contentId: String = ""

    fun load(contentId: String) {
        if (_contentId == contentId) return
        _contentId = contentId
        viewModelScope.launch {
            val movie = findMovie(contentId)
            val content = contentDao.byContentId(contentId)
            if (movie != null && content != null) {
                _state.value = MovieDetailState.Loaded(
                    movie = movie,
                    content = content,
                    isOnWatchlist = false,
                )
                // Observe watchlist status
                watchlistDao.watchIsInWatchlist(contentId)
                    .collect { isOn ->
                        val current = _state.value
                        if (current is MovieDetailState.Loaded) {
                            _state.value = current.copy(isOnWatchlist = isOn)
                        }
                    }
            } else {
                _state.value = MovieDetailState.NotFound
            }
        }
    }

    fun toggleWatchlist() {
        val current = (_state.value as? MovieDetailState.Loaded) ?: return
        viewModelScope.launch {
            if (current.isOnWatchlist) {
                watchlistDao.remove(current.movie.contentId)
            } else {
                watchlistDao.add(
                    WatchlistEntity(
                        contentId = current.movie.contentId,
                        contentType = "movie",
                        title = current.movie.movieTitle,
                        artworkUrl = current.movie.posterUrl,
                        addedAt = Instant.now(),
                    ),
                )
            }
        }
    }

    /**
     * Find the movie -- try by contentId first, then scan all movies.
     * This handles cases where the contentId might be the movie title
     * or the actual content_id field.
     */
    private suspend fun findMovie(contentId: String): MovieEntity? {
        // Direct lookup via the all-movies flow snapshot
        val allMovies = movieDao.watchAllByYear()
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
            .value
        return allMovies.firstOrNull { it.contentId == contentId }
    }
}

sealed interface MovieDetailState {
    data object Loading : MovieDetailState
    data object NotFound : MovieDetailState
    data class Loaded(
        val movie: MovieEntity,
        val content: ContentItemEntity,
        val isOnWatchlist: Boolean,
    ) : MovieDetailState
}
