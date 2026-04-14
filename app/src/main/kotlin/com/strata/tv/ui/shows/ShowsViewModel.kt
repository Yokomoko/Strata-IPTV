package com.strata.tv.ui.shows

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.strata.tv.data.db.SeriesDao
import com.strata.tv.data.db.SeriesEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Drives [ShowsScreen].
 *
 * Streams all visible series, grouped by genre for the rail layout.
 */
@HiltViewModel
class ShowsViewModel @Inject constructor(
    private val seriesDao: SeriesDao,
) : ViewModel() {

    val state: StateFlow<ShowsUiState> = seriesDao.watchAll()
        .map { allSeries ->
            val hero = allSeries.firstOrNull { it.posterUrl.isNotBlank() }
            val genreRails = buildGenreRails(allSeries)
            ShowsUiState(
                hero = hero,
                allShows = allSeries,
                genreRails = genreRails,
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ShowsUiState.Empty,
        )

    private fun buildGenreRails(series: List<SeriesEntity>): List<ShowGenreRail> {
        val byGenre = mutableMapOf<String, MutableList<SeriesEntity>>()
        for (show in series) {
            val genres = show.genre.split(",", "|", "/")
                .map { it.trim() }
                .filter { it.isNotBlank() }
            if (genres.isEmpty()) {
                byGenre.getOrPut("Uncategorised") { mutableListOf() }.add(show)
            } else {
                val primary = genres.first()
                byGenre.getOrPut(primary) { mutableListOf() }.add(show)
            }
        }
        return byGenre.entries
            .filter { it.value.size >= 3 }
            .sortedByDescending { it.value.size }
            .take(15)
            .map { (genre, items) -> ShowGenreRail(genre, items) }
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
