package com.strata.tv.ui.shows

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.strata.tv.data.db.EpisodeDao
import com.strata.tv.data.db.SeriesDao
import com.strata.tv.data.db.SeriesEntity
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
 * Drives [ShowsScreen].  Reads the full visible-series list from Room,
 * derives the unique genre set, and applies genre filtering driven by
 * the UI's chip row.
 *
 * Playback resolution — no episode-picker screen yet, so a click on a
 * series plays the first available episode (lowest season / episode
 * numbers).  The episode lookup happens off the main thread and the
 * resulting [PlayerArgs] is emitted through [playEvents].
 */
@HiltViewModel
class ShowsViewModel @Inject constructor(
    seriesDao: SeriesDao,
    private val episodeDao: EpisodeDao,
) : ViewModel() {

    private val _selectedGenre = MutableStateFlow("All")

    val state: StateFlow<ShowsUiState> = combine(
        seriesDao.watchAll(),
        _selectedGenre,
    ) { items, selectedGenre ->
        val genres = listOf("All") + items
            .flatMap { it.genre.split(", ").filter { g -> g.isNotBlank() } }
            .distinct()
            .sorted()

        val filtered = if (selectedGenre == "All") items
        else items.filter { selectedGenre in it.genre }

        ShowsUiState(
            allShows = items,
            genres = genres,
            selectedGenre = selectedGenre,
            filteredShows = filtered,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ShowsUiState.Empty,
    )

    fun selectGenre(genre: String) {
        _selectedGenre.value = genre
    }

    // -- Playback events --------------------------------------------------

    private val _playEvents = MutableSharedFlow<PlayerArgs>(extraBufferCapacity = 1)
    val playEvents: SharedFlow<PlayerArgs> = _playEvents.asSharedFlow()

    fun playSeries(series: SeriesEntity) {
        viewModelScope.launch {
            val episode = episodeDao.firstOf(series.seriesTitle) ?: return@launch
            val title = buildString {
                append(series.seriesTitle)
                append(" — S")
                append(episode.seasonNumber.toString().padStart(2, '0'))
                append("E")
                append(episode.episodeNumber.toString().padStart(2, '0'))
                if (episode.episodeTitle.isNotBlank()) {
                    append(": ").append(episode.episodeTitle)
                }
            }
            _playEvents.emit(
                PlayerArgs(
                    streamUrl = episode.streamUrl,
                    title = title,
                    isLive = false,
                    resumePositionMs = 0,
                    contentType = "show",
                    artworkUrl = series.posterUrl,
                ),
            )
        }
    }
}

/**
 * Snapshot of the shows screen's view state.
 */
data class ShowsUiState(
    val allShows: List<SeriesEntity>,
    val genres: List<String>,
    val selectedGenre: String,
    val filteredShows: List<SeriesEntity>,
) {
    companion object {
        val Empty = ShowsUiState(
            allShows = emptyList(),
            genres = listOf("All"),
            selectedGenre = "All",
            filteredShows = emptyList(),
        )
    }
}
