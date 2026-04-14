package com.strata.tv.ui.home

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.strata.tv.AppConfig
import com.strata.tv.data.db.ChannelDao
import com.strata.tv.data.db.ContinueWatchingDao
import com.strata.tv.data.db.ContinueWatchingEntity
import com.strata.tv.data.db.MovieDao
import com.strata.tv.data.db.MovieEntity
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

    @Suppress("UNCHECKED_CAST")
    val state: StateFlow<HomeUiState> = combine(
        movieDao.watchVisibleCount(),
        seriesDao.watchCount(),
        channelDao.watchAll(),
        cwDao.watchAll(),
        movieDao.watchRecentWithPosters(limit = 20),
        movieDao.watchHeroCandidates(limit = 5),
    ) { values ->
        val movies = values[0] as Int
        val series = values[1] as Int
        val channels = values[2] as List<*>
        val cw = values[3] as List<ContinueWatchingEntity>
        val recentMovies = values[4] as List<MovieListItem>
        val heroCandidates = values[5] as List<MovieEntity>
        HomeUiState(
            channelCount = channels.size,
            movieCount = movies,
            seriesCount = series,
            continueWatching = cw,
            recentMovies = recentMovies,
            heroCandidates = heroCandidates,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HomeUiState.Empty,
    )

    // -- Genre + provider rails (built in background) ------------------

    private val _genreRails = MutableStateFlow<List<HomeGenreRail>>(emptyList())
    val genreRails: StateFlow<List<HomeGenreRail>> = _genreRails.asStateFlow()

    private val _providerRails = MutableStateFlow<List<HomeProviderRail>>(emptyList())
    val providerRails: StateFlow<List<HomeProviderRail>> = _providerRails.asStateFlow()

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

        // Build genre + provider rails once enrichment starts populating data.
        viewModelScope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(5_000)
            buildGenreRails()
            buildProviderRails()
            // Rebuild after enrichment has had more time.
            kotlinx.coroutines.delay(30_000)
            buildGenreRails()
            buildProviderRails()
            // One more pass at 2 min for fuller coverage.
            kotlinx.coroutines.delay(90_000)
            buildGenreRails()
            buildProviderRails()
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

    private suspend fun buildProviderRails() {
        try {
            val providers = movieDao.countsByProvider()
            // Skip "Unknown" and take top 6 by count.
            val topProviders = providers
                .filter { it.provider != "Unknown" && it.provider.isNotBlank() }
                .sortedByDescending { it.count }
                .take(6)

            val rails = topProviders.mapNotNull { prov ->
                val movies = movieDao.byProviderForList(prov.provider, limit = 20)
                if (movies.size >= 3) {
                    HomeProviderRail("New on ${prov.provider}", movies)
                } else null
            }
            _providerRails.value = rails
        } catch (e: Throwable) {
            Log.w("HomeViewModel", "Provider rail build failed", e)
        }
    }
}

/** A provider-grouped rail for the Home screen. */
data class HomeProviderRail(
    val title: String,
    val movies: List<MovieListItem>,
)

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
    val heroCandidates: List<MovieEntity> = emptyList(),
) {
    companion object {
        val Empty = HomeUiState(
            channelCount = 0,
            movieCount = 0,
            seriesCount = 0,
            continueWatching = emptyList(),
            recentMovies = emptyList(),
            heroCandidates = emptyList(),
        )
    }
}
