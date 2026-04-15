package com.strata.tv.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.strata.tv.data.db.ContentDao
import com.strata.tv.data.db.ContentItemEntity
import com.strata.tv.domain.FuzzyMatch
import com.strata.tv.domain.TitleParser
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Drives [SearchScreen].
 *
 * The user types into the text field → [onQueryChanged] updates the
 * internal query flow → after a 250 ms debounce the flow fires,
 * calling [ContentDao.searchRaw] (word-split LIKE pre-filter, max 500
 * rows) and then ranking each hit with [FuzzyMatch.fuzzyScore].
 *
 * Show episodes are deduplicated by their parsed series title so that
 * searching "Breaking" returns one "Breaking Bad" entry instead of 60
 * individual episodes.  The dedup key is [TitleParser.normalise] of
 * the `title` field (which the sync pipeline fills with the series
 * title), falling back to the raw `displayName` when `title` is empty.
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val contentDao: ContentDao,
) : ViewModel() {

    private val _query = MutableStateFlow("")

    /** Current query text, exposed so the screen can bind it to the text field. */
    val query: StateFlow<String> = _query

    /** Debounced, scored, grouped search results. */
    val results: StateFlow<SearchUiState> = _query
        .debounce(250L)
        .mapLatest { q -> executeSearch(q) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = SearchUiState.Empty,
        )

    fun onQueryChanged(newQuery: String) {
        _query.value = newQuery
    }

    // -----------------------------------------------------------------
    // Internal
    // -----------------------------------------------------------------

    private suspend fun executeSearch(query: String): SearchUiState {
        if (query.length < 2) return SearchUiState.Empty

        val builtQuery = ContentDao.buildSearchQuery(query)
        val raw = contentDao.searchRaw(builtQuery)
        if (raw.isEmpty()) return SearchUiState.NoResults

        // Score + filter (drop anything that scores 0).
        val scored = raw.mapNotNull { item ->
            val best = maxOf(
                FuzzyMatch.fuzzyScore(query, item.displayName),
                FuzzyMatch.fuzzyScore(query, item.title),
                FuzzyMatch.fuzzyScore(query, item.tvgName),
            )
            if (best > 0.0) ScoredItem(item, best) else null
        }.sortedByDescending { it.score }

        // Partition by content type.
        val channels = mutableListOf<SearchResult>()
        val movies = mutableListOf<SearchResult>()
        val shows = mutableListOf<SearchResult>()

        // For show dedup: track which normalised series titles we've already added.
        val seenSeries = mutableSetOf<String>()

        for ((item, score) in scored) {
            when (item.contentType) {
                "live" -> channels.add(item.toSearchResult(score))
                "movie" -> movies.add(item.toSearchResult(score))
                "show" -> {
                    // Dedup episodes into a single series entry.
                    val seriesKey = seriesKeyFor(item)
                    if (seenSeries.add(seriesKey)) {
                        shows.add(item.toSeriesResult(seriesKey, score))
                    }
                }
            }
        }

        return SearchUiState.Results(
            channels = channels,
            movies = movies,
            shows = shows,
        )
    }

    /**
     * Derive a dedup key for show episodes.  Uses [TitleParser.normalise]
     * on the `title` field (which the sync pipeline fills with the parsed
     * series title) so that "HD : Breaking Bad S01E01" and
     * "HD : Breaking Bad S03E07" collapse to the same key.
     */
    private fun seriesKeyFor(item: ContentItemEntity): String {
        val base = item.title.ifBlank {
            // Fallback: try to parse the display name for a series title.
            TitleParser.parseEpisode(item.displayName)?.seriesTitle
                ?: TitleParser.stripHdPrefix(item.displayName)
        }
        return TitleParser.normalise(base)
    }

    private fun ContentItemEntity.toSearchResult(score: Double) = SearchResult(
        contentId = contentId,
        displayName = displayName,
        title = title.ifBlank { TitleParser.stripHdPrefix(displayName) },
        groupTitle = groupTitle,
        contentType = contentType,
        streamUrl = streamUrl,
        artworkUrl = artworkUrl,
        score = score,
    )

    private fun ContentItemEntity.toSeriesResult(seriesKey: String, score: Double) = SearchResult(
        contentId = contentId,
        displayName = displayName,
        title = title.ifBlank { TitleParser.stripHdPrefix(displayName) },
        groupTitle = groupTitle,
        contentType = contentType,
        streamUrl = streamUrl,
        artworkUrl = artworkUrl,
        score = score,
        seriesTitle = seriesKey,
    )
}

// =====================================================================
// Models
// =====================================================================

/** A single search result row, already scored and ready for display. */
data class SearchResult(
    val contentId: String,
    val displayName: String,
    val title: String,
    val groupTitle: String,
    val contentType: String,
    val streamUrl: String,
    val artworkUrl: String,
    val score: Double,
    /** Non-empty only for show entries; the normalised series title. */
    val seriesTitle: String = "",
)

/** Sealed state for the search screen. */
sealed interface SearchUiState {
    data object Empty : SearchUiState
    data object NoResults : SearchUiState
    data class Results(
        val channels: List<SearchResult>,
        val movies: List<SearchResult>,
        val shows: List<SearchResult>,
    ) : SearchUiState
}

/** Internal pair used during scoring before partitioning. */
private data class ScoredItem(val item: ContentItemEntity, val score: Double)
