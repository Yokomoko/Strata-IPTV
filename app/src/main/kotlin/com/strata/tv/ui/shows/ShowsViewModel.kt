package com.strata.tv.ui.shows

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.strata.tv.data.db.SeriesDao
import com.strata.tv.data.db.SeriesEntity
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
 * Drives [ShowsScreen].
 *
 * Streams all visible series, grouped by genre for the rail layout.
 * Matches the structure of [MoviesViewModel] — rails rebuild on a
 * separate flow gated by genre-signature distinctness so enrichment
 * writes to poster/overview don't re-run the grouping pass during
 * scroll.
 */
@HiltViewModel
class ShowsViewModel @Inject constructor(
    private val seriesDao: SeriesDao,
    private val watchlistDao: WatchlistDao,
    private val libraryFilter: com.strata.tv.data.repo.LibraryFilterRepository,
) : ViewModel() {

    private val shows = seriesDao.watchAll()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    private val genreRails = shows
        .distinctUntilChangedBy { list ->
            list.map { it.seriesTitle to it.genre }.sortedBy { it.first }
        }
        .map { list -> GenreGrouper.groupSeries(list) }
        .flowOn(Dispatchers.Default)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    val state: StateFlow<ShowsUiState> = combine(shows, genreRails) { list, rails ->
        ShowsUiState(
            hero = list.firstOrNull { it.posterUrl.isNotBlank() },
            allShows = list,
            genreRails = rails,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ShowsUiState.Empty,
    )

    // -- Watchlist --------------------------------------------------------

    val watchlistIds: StateFlow<Set<String>> = watchlistDao.watchAll()
        .map { list -> list.map { it.contentId }.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    fun addToWatchlist(seriesTitle: String, artworkUrl: String) {
        viewModelScope.launch {
            watchlistDao.add(
                WatchlistEntity(
                    contentId = seriesTitle,
                    contentType = "show",
                    title = seriesTitle,
                    artworkUrl = artworkUrl,
                ),
            )
        }
    }

    fun removeFromWatchlist(seriesTitle: String) {
        viewModelScope.launch { watchlistDao.remove(seriesTitle) }
    }

    fun hideSeries(seriesTitle: String) {
        viewModelScope.launch { libraryFilter.hideSeries(seriesTitle) }
    }

    fun ignoreGenre(genre: String) {
        viewModelScope.launch { libraryFilter.ignoreGenre(genre) }
    }

    fun ignoreLanguage(languageCode: String) {
        viewModelScope.launch { libraryFilter.ignoreLanguage(languageCode) }
    }
}

data class ShowGenreRail(
    val genre: String,
    val shows: List<SeriesEntity>,
)

data class ShowsUiState(
    val hero: SeriesEntity?,
    val allShows: List<SeriesEntity>,
    val genreRails: List<ShowGenreRail>,
) {
    companion object {
        val Empty = ShowsUiState(
            hero = null,
            allShows = emptyList(),
            genreRails = emptyList(),
        )
    }
}
