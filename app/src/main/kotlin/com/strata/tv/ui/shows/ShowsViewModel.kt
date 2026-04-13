package com.strata.tv.ui.shows

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.strata.tv.data.db.SeriesDao
import com.strata.tv.data.db.SeriesEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Drives [ShowsScreen].  Reads the full visible-series list from Room,
 * derives the unique genre set, and applies genre filtering driven by
 * the UI's chip row.
 */
@HiltViewModel
class ShowsViewModel @Inject constructor(
    seriesDao: SeriesDao,
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
