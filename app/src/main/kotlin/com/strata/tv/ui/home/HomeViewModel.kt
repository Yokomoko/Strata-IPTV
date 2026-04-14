package com.strata.tv.ui.home

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.strata.tv.AppConfig
import com.strata.tv.data.db.ChannelDao
import com.strata.tv.data.db.ContinueWatchingDao
import com.strata.tv.data.db.ContinueWatchingEntity
import com.strata.tv.data.db.MovieDao
import com.strata.tv.data.db.MovieListItem
import com.strata.tv.data.db.SeriesDao
import com.strata.tv.data.repo.BootstrapRepository
import com.strata.tv.data.repo.SyncService
import com.strata.tv.data.tmdb.EnrichmentProgressTracker
import com.strata.tv.data.tmdb.MovieEnrichmentService
import com.strata.tv.data.tmdb.SeriesEnrichmentService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Drives [HomeScreen].
 *
 * Combines lightweight flows (counts, continue-watching, recent movies
 * with posters) into a single [HomeUiState] for the core layout, then
 * builds genre rails in a background coroutine to avoid blocking the
 * first render while genre grouping runs.
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val bootstrap: BootstrapRepository,
    private val syncService: SyncService,
    private val movieEnrichment: MovieEnrichmentService,
    private val seriesEnrichment: SeriesEnrichmentService,
    private val enrichmentTracker: EnrichmentProgressTracker,
    private val movieDao: MovieDao,
    private val seriesDao: SeriesDao,
    private val channelDao: ChannelDao,
    private val cwDao: ContinueWatchingDao,
) : ViewModel() {

    // -- Core state (lightweight flows) --------------------------------

    val state: StateFlow<HomeUiState> = combine(
        movieDao.watchVisibleCount(),
        seriesDao.watchCount(),
        channelDao.watchAll(),
        cwDao.watchAll(),
        movieDao.watchRecentWithPosters(limit = 20),
    ) { movies, series, channels, cw, recentMovies ->
        HomeUiState(
            channelCount = channels.size,
            movieCount = movies,
            seriesCount = series,
            continueWatching = cw,
            recentMovies = recentMovies,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HomeUiState.Empty,
    )

    // -- Genre rails (built in background) -----------------------------

    private val _genreRails = MutableStateFlow<List<HomeGenreRail>>(emptyList())
    val genreRails: StateFlow<List<HomeGenreRail>> = _genreRails.asStateFlow()

    /** Sync progress, surfaced separately so the UI can show a banner. */
    val syncProgress: StateFlow<SyncService.Progress> = syncService.progress

    init {
        viewModelScope.launch {
            val sourceId = bootstrap.ensureSource()
            val firstCount = movieDao.watchVisibleCount().first()
            if (firstCount == 0) {
                runCatching { syncService.syncFromUrl(AppConfig.PLAYLIST_URL, sourceId) }
            }

            // Kick off enrichment.
            enrichmentTracker.reset()
            val movieJob = launch(Dispatchers.IO) { runCatching { movieEnrichment.enrichBatch() } }
            val seriesJob = launch(Dispatchers.IO) { runCatching { seriesEnrichment.enrichBatch() } }
            launch {
                movieJob.join()
                seriesJob.join()
                enrichmentTracker.finish()
            }
        }

        // Build genre rails once enrichment starts populating genres.
        // Re-runs periodically while enrichment is active.
        viewModelScope.launch(Dispatchers.IO) {
            // Wait a bit for initial sync + early enrichment to populate some genres.
            kotlinx.coroutines.delay(5_000)
            buildGenreRails()
            // Rebuild once more after enrichment has had time to work.
            kotlinx.coroutines.delay(30_000)
            buildGenreRails()
        }
    }

    private suspend fun buildGenreRails() {
        try {
            val rawGenres = movieDao.distinctGenres()
            // Each row is a comma-separated genre string; split + count occurrences.
            val genreCounts = mutableMapOf<String, Int>()
            for (raw in rawGenres) {
                raw.split(",", "|", "/").map { it.trim() }.filter { it.isNotBlank() }.forEach {
                    genreCounts[it] = (genreCounts[it] ?: 0) + 1
                }
            }
            // Take top 8 genres by frequency.
            val topGenres = genreCounts.entries
                .filter { it.value >= 3 }
                .sortedByDescending { it.value }
                .take(8)
                .map { it.key }

            val rails = topGenres.mapNotNull { genre ->
                val movies = movieDao.byGenre(genre, limit = 20)
                if (movies.size >= 3) HomeGenreRail(genre, movies) else null
            }
            _genreRails.value = rails
        } catch (e: Throwable) {
            Log.w("HomeViewModel", "Genre rail build failed", e)
        }
    }
}

/** A genre-grouped rail for the Home screen. */
data class HomeGenreRail(
    val genre: String,
    val movies: List<MovieListItem>,
)

data class HomeUiState(
    val channelCount: Int,
    val movieCount: Int,
    val seriesCount: Int,
    val continueWatching: List<ContinueWatchingEntity>,
    val recentMovies: List<MovieListItem>,
) {
    companion object {
        val Empty = HomeUiState(
            channelCount = 0,
            movieCount = 0,
            seriesCount = 0,
            continueWatching = emptyList(),
            recentMovies = emptyList(),
        )
    }
}
