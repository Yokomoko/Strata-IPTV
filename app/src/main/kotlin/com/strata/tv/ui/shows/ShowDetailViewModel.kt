package com.strata.tv.ui.shows

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.strata.tv.data.db.ContentDao
import com.strata.tv.data.db.EpisodeDao
import com.strata.tv.data.db.EpisodeEntity
import com.strata.tv.data.db.SeriesDao
import com.strata.tv.data.db.SeriesEntity
import com.strata.tv.data.db.WatchlistDao
import com.strata.tv.data.db.WatchlistEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

/**
 * Drives [ShowDetailScreen].
 *
 * Loads a series by title, streams its episodes grouped by season,
 * and manages watchlist toggle.
 */
@HiltViewModel
class ShowDetailViewModel @Inject constructor(
    private val seriesDao: SeriesDao,
    private val episodeDao: EpisodeDao,
    private val contentDao: ContentDao,
    private val watchlistDao: WatchlistDao,
) : ViewModel() {

    private val _state = MutableStateFlow<ShowDetailState>(ShowDetailState.Loading)
    val state: StateFlow<ShowDetailState> = _state.asStateFlow()

    private val _selectedSeason = MutableStateFlow(1)
    val selectedSeason: StateFlow<Int> = _selectedSeason.asStateFlow()

    private var _seriesTitle: String = ""

    /**
     * Current load job — cancelled and replaced on each `load()` so a
     * previous series' episode+watchlist flow subscription doesn't leak
     * until the whole ViewModel scope dies (idioms review #3).
     */
    private var loadJob: Job? = null

    fun load(seriesTitle: String) {
        if (_seriesTitle == seriesTitle) return
        _seriesTitle = seriesTitle
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val series = seriesDao.byTitle(seriesTitle)
            if (series == null) {
                _state.value = ShowDetailState.NotFound
                return@launch
            }

            // Observe episodes + watchlist together
            combine(
                episodeDao.watchSeries(seriesTitle),
                watchlistDao.watchIsInWatchlist(seriesTitle),
            ) { episodes, isOnWatchlist ->
                val seasons = episodes
                    .groupBy { it.seasonNumber }
                    .toSortedMap()
                ShowDetailState.Loaded(
                    series = series,
                    seasons = seasons,
                    isOnWatchlist = isOnWatchlist,
                )
            }.collect { _state.value = it }
        }
    }

    fun selectSeason(season: Int) {
        _selectedSeason.value = season
    }

    fun toggleWatchlist() {
        val current = (_state.value as? ShowDetailState.Loaded) ?: return
        viewModelScope.launch {
            if (current.isOnWatchlist) {
                watchlistDao.remove(_seriesTitle)
            } else {
                watchlistDao.add(
                    WatchlistEntity(
                        contentId = _seriesTitle,
                        contentType = "show",
                        title = current.series.seriesTitle,
                        artworkUrl = current.series.posterUrl,
                        addedAt = Instant.now(),
                    ),
                )
            }
        }
    }

    /**
     * Resolve the stream URL for an episode by looking up the content item.
     */
    suspend fun resolveStreamUrl(episode: EpisodeEntity): String {
        return episode.streamUrl.ifBlank {
            contentDao.byContentId(episode.contentId)?.streamUrl ?: ""
        }
    }
}

sealed interface ShowDetailState {
    data object Loading : ShowDetailState
    data object NotFound : ShowDetailState
    data class Loaded(
        val series: SeriesEntity,
        val seasons: Map<Int, List<EpisodeEntity>>,
        val isOnWatchlist: Boolean,
    ) : ShowDetailState
}
