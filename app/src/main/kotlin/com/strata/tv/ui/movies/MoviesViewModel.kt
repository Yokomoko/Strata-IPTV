package com.strata.tv.ui.movies

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.strata.tv.data.db.ContentDao
import com.strata.tv.data.db.MovieDao
import com.strata.tv.data.db.MovieEntity
import com.strata.tv.ui.nav.PlayerArgs
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Drives [MoviesScreen].  Reads the full visible-movie list from Room,
 * derives the unique genre set, and applies genre filtering driven by
 * the UI's chip row.
 *
 * Playback resolution — [MovieEntity] doesn't carry a stream URL (that
 * lives on the matching `ContentItemEntity` keyed by `content_id`), so
 * a click calls [playMovie], which looks the URL up off the main thread
 * and emits a ready-to-use [PlayerArgs] via [playEvents].
 */
@HiltViewModel
class MoviesViewModel @Inject constructor(
    movieDao: MovieDao,
    private val contentDao: ContentDao,
) : ViewModel() {

    private val _selectedGenre = MutableStateFlow("All")

    val state: StateFlow<MoviesUiState> = combine(
        movieDao.watchAllByYear(),
        _selectedGenre,
    ) { items, selectedGenre ->
        val genres = listOf("All") + items
            .flatMap { it.genre.split(", ").filter { g -> g.isNotBlank() } }
            .distinct()
            .sorted()

        val filtered = if (selectedGenre == "All") items
        else items.filter { selectedGenre in it.genre }

        MoviesUiState(
            allMovies = items,
            genres = genres,
            selectedGenre = selectedGenre,
            filteredMovies = filtered,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MoviesUiState.Empty,
    )

    fun selectGenre(genre: String) {
        _selectedGenre.value = genre
    }

    // -- Playback events --------------------------------------------------

    private val _playEvents = MutableSharedFlow<PlayerArgs>(extraBufferCapacity = 1)
    val playEvents: SharedFlow<PlayerArgs> = _playEvents.asSharedFlow()

    fun playMovie(movie: MovieEntity) {
        viewModelScope.launch {
            val item = contentDao.byContentId(movie.contentId) ?: return@launch
            _playEvents.emit(
                PlayerArgs(
                    streamUrl = item.streamUrl,
                    title = movie.movieTitle,
                    isLive = false,
                    resumePositionMs = movie.resumePositionMs,
                    contentType = "movie",
                    artworkUrl = movie.posterUrl,
                ),
            )
        }
    }
}

/**
 * Snapshot of the movies screen's view state.
 */
data class MoviesUiState(
    val allMovies: List<MovieEntity>,
    val genres: List<String>,
    val selectedGenre: String,
    val filteredMovies: List<MovieEntity>,
) {
    companion object {
        val Empty = MoviesUiState(
            allMovies = emptyList(),
            genres = listOf("All"),
            selectedGenre = "All",
            filteredMovies = emptyList(),
        )
    }
}
