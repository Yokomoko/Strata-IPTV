package com.strata.tv.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.strata.tv.data.db.ContentDao
import com.strata.tv.data.db.ContentItemEntity
import com.strata.tv.data.db.MovieDao
import com.strata.tv.data.db.SeriesDao
import com.strata.tv.data.db.WatchlistDao
import com.strata.tv.data.db.WatchlistEntity
import com.strata.tv.domain.FuzzyMatch
import com.strata.tv.domain.TitleParser
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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
    private val movieDao: MovieDao,
    private val seriesDao: SeriesDao,
    private val watchlistDao: WatchlistDao,
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

    /**
     * Snapshot of every contentId currently on the watchlist, so the
     * search context menu knows whether to show "Add to Watchlist" or
     * "Remove from Watchlist" without an extra round-trip per row.
     */
    val watchlistIds: StateFlow<Set<String>> = watchlistDao.watchAll()
        .map { entries -> entries.map { it.contentId }.toSet() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptySet(),
        )

    fun onQueryChanged(newQuery: String) {
        _query.value = newQuery
    }

    fun addToWatchlist(result: SearchResult) {
        viewModelScope.launch {
            // Shows are keyed by series title for parity with the show
            // detail screen and the Watchlist rail rendering.
            val (id, type) = if (result.contentType == "show" && result.seriesTitle.isNotBlank()) {
                result.seriesTitle to "show"
            } else {
                result.contentId to result.contentType
            }
            watchlistDao.add(
                WatchlistEntity(
                    contentId = id,
                    contentType = type,
                    title = result.title,
                    artworkUrl = result.artworkUrl,
                ),
            )
        }
    }

    fun removeFromWatchlist(result: SearchResult) {
        viewModelScope.launch {
            val id = if (result.contentType == "show" && result.seriesTitle.isNotBlank()) {
                result.seriesTitle
            } else {
                result.contentId
            }
            watchlistDao.remove(id)
        }
    }

    // -----------------------------------------------------------------
    // Internal
    // -----------------------------------------------------------------

    private suspend fun executeSearch(query: String): SearchUiState {
        if (query.length < 2) return SearchUiState.Empty

        val builtQuery = ContentDao.buildSearchQuery(query)
        val raw = contentDao.searchRaw(builtQuery)
        if (raw.isEmpty()) return SearchUiState.NoResults

        // Score + tie-breaker boosts (issue #36).  Vanilla fuzzy score
        // returns 1.0 for any substring match, so "LEGO Marvel Avengers"
        // and "Avengers: Endgame" tie at 1.0 for query "Avengers" and
        // the LEGO entry can win on sort stability.  We add explicit
        // boosts so exact / prefix matches always sort first.
        val q = query.trim().lowercase()
        val scored = raw.mapNotNull { item ->
            val best = maxOf(
                FuzzyMatch.fuzzyScore(query, item.displayName),
                FuzzyMatch.fuzzyScore(query, item.title),
                FuzzyMatch.fuzzyScore(query, item.tvgName),
            )
            if (best <= 0.0) return@mapNotNull null

            val cleanTitle = item.title.ifBlank { item.displayName }
                .lowercase()
                .trim()
            val boost = when {
                cleanTitle == q -> 1.0                 // exact title match
                cleanTitle.startsWith("$q ") -> 0.6    // starts with full word
                cleanTitle.startsWith("$q:") -> 0.6    // "Avengers: Endgame"
                cleanTitle.startsWith(q) -> 0.4        // prefix
                " $q " in " $cleanTitle " -> 0.2       // whole word elsewhere
                else -> 0.0
            }
            ScoredItem(item, best + boost)
        }.sortedByDescending { it.score }

        // Partition by content type, with dedup by normalised title for
        // movies (quality variants leak through — "Friend Game" showed
        // up twice, once as 1080p and once as 720p) and by series for
        // shows (one card per series, not per episode).
        val channels = mutableListOf<SearchResult>()
        val movies = mutableListOf<SearchResult>()
        val shows = mutableListOf<SearchResult>()
        val seenMovies = mutableSetOf<String>()
        val seenSeries = mutableSetOf<String>()

        for ((item, score) in scored) {
            when (item.contentType) {
                "live" -> channels.add(item.toSearchResult(score))
                "movie" -> {
                    val movieKey = movieKeyFor(item)
                    if (seenMovies.add(movieKey)) {
                        movies.add(item.toSearchResult(score))
                    }
                }
                "show" -> {
                    val seriesKey = seriesKeyFor(item)
                    if (seenSeries.add(seriesKey)) {
                        shows.add(item.toSeriesResult(seriesKey, score))
                    }
                }
            }
        }

        // Batch-fetch poster URLs from the movies + series tables.
        // content_items doesn't carry the TMDB poster URL; it lives on
        // movies.poster_url / series.poster_url after enrichment.  Two
        // IN-clause lookups are cheaper than 60+60 individual fetches
        // and keeps the search response well under the 250 ms debounce.
        val moviePosters = if (movies.isNotEmpty()) {
            movieDao.postersForContentIds(movies.map { it.contentId })
                .associate { it.contentId to it.posterUrl }
        } else emptyMap()
        val seriesPosters = if (shows.isNotEmpty()) {
            seriesDao.postersForTitles(shows.map { it.seriesTitle })
                .associate { it.seriesTitle.lowercase() to it.posterUrl }
        } else emptyMap()

        return SearchUiState.Results(
            channels = channels,
            movies = movies.map { it.copy(posterUrl = moviePosters[it.contentId].orEmpty()) },
            shows = shows.map {
                it.copy(posterUrl = seriesPosters[it.seriesTitle.lowercase()].orEmpty())
            },
        )
    }

    /** Dedup key for movies — normalised title strips quality tags / HD prefixes. */
    private fun movieKeyFor(item: ContentItemEntity): String {
        val base = item.title.ifBlank { TitleParser.stripHdPrefix(item.displayName) }
        return TitleParser.normalise(base)
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
    /**
     * TMDB poster URL for the result.  Populated from
     * `movies.poster_url` / `series.poster_url` during [SearchViewModel.executeSearch].
     * Empty when enrichment hasn't run for this item yet.
     */
    val posterUrl: String = "",
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
