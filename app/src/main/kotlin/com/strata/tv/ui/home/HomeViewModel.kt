package com.strata.tv.ui.home

import android.app.Application
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.strata.tv.AppConfig
import com.strata.tv.data.db.ChannelDao
import com.strata.tv.data.db.ContentDao
import com.strata.tv.data.db.ContinueWatchingDao
import com.strata.tv.data.db.ContinueWatchingEntity
import com.strata.tv.data.db.MovieDao
import com.strata.tv.data.db.MovieEntity
import com.strata.tv.data.db.MovieListItem
import com.strata.tv.data.db.SeriesDao
import com.strata.tv.data.db.SourceDao
import com.strata.tv.data.db.WatchlistDao
import com.strata.tv.data.db.WatchlistEntity
import com.strata.tv.data.repo.BootstrapRepository
import com.strata.tv.data.repo.SyncService
import com.strata.tv.data.repo.SyncWorker
import com.strata.tv.data.tmdb.EnrichmentProgressTracker
import com.strata.tv.data.tmdb.MovieEnrichmentService
import com.strata.tv.data.tmdb.SeriesEnrichmentService
import com.strata.tv.domain.MovieDeduplicator
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
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
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
    private val application: Application,
    private val bootstrap: BootstrapRepository,
    private val syncService: SyncService,
    private val movieDeduplicator: MovieDeduplicator,
    private val movieEnrichment: MovieEnrichmentService,
    private val seriesEnrichment: SeriesEnrichmentService,
    private val enrichmentTracker: EnrichmentProgressTracker,
    private val contentDao: ContentDao,
    private val movieDao: MovieDao,
    private val seriesDao: SeriesDao,
    private val channelDao: ChannelDao,
    private val cwDao: ContinueWatchingDao,
    private val watchlistDao: WatchlistDao,
    private val sourceDao: SourceDao,
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

    /** Watchlist items for the Home rail. */
    val watchlist: StateFlow<List<WatchlistEntity>> = watchlistDao.watchAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // -- Genre + provider rails (built in background) ------------------

    private val _genreRails = MutableStateFlow<List<HomeGenreRail>>(emptyList())
    val genreRails: StateFlow<List<HomeGenreRail>> = _genreRails.asStateFlow()

    private val _providerRails = MutableStateFlow<List<HomeProviderRail>>(emptyList())
    val providerRails: StateFlow<List<HomeProviderRail>> = _providerRails.asStateFlow()

    /** Sync progress, surfaced separately so the UI can show a banner. */
    val syncProgress: StateFlow<SyncService.Progress> = syncService.progress

    // -- Context menu actions ---------------------------------------------

    /** Remove a Continue Watching entry by its content ID. */
    fun removeContinueWatching(contentId: String) {
        viewModelScope.launch { cwDao.delete(contentId) }
    }

    /** Add a movie to the Watchlist. */
    fun addToWatchlist(contentId: String, title: String, artworkUrl: String) {
        viewModelScope.launch {
            watchlistDao.add(
                WatchlistEntity(
                    contentId = contentId,
                    contentType = "movie",
                    title = title,
                    artworkUrl = artworkUrl,
                ),
            )
        }
    }

    /** Remove a movie from the Watchlist. */
    fun removeFromWatchlist(contentId: String) {
        viewModelScope.launch { watchlistDao.remove(contentId) }
    }

    /** Check if a movie is in the Watchlist (snapshot, not flow). */
    suspend fun isInWatchlist(contentId: String): Boolean =
        watchlistDao.watchIsInWatchlist(contentId).first()

    init {
        // Schedule periodic background sync via WorkManager (every 12h,
        // network-required).  This replaces the old forced-every-launch
        // sync and keeps the library fresh without blocking app start.
        schedulePeriodicSync()

        viewModelScope.launch {
            val sourceId = bootstrap.ensureSource()
            enrichmentTracker.startSync()

            // Only sync if first run (0 movies) or stale (>24h).
            // WorkManager handles periodic background sync.
            val movieCount = movieDao.watchVisibleCount().first()
            val source = sourceDao.all().firstOrNull()
            val lastSynced = source?.lastSynced
            val stale = lastSynced == null ||
                Duration.between(lastSynced, Instant.now()).toHours() >= 24

            if (movieCount == 0 || stale) {
                runCatching { syncService.syncFromUrl(AppConfig.PLAYLIST_URL, sourceId) }
                    .onSuccess {
                        runCatching { sourceDao.markSynced(sourceId, Instant.now()) }
                    }
            }

            // Diagnostic: log content counts by type
            launch(Dispatchers.IO) {
                val allContent = contentDao.byType("movie")
                val showContent = contentDao.byType("show")
                val liveContent = contentDao.byType("live")
                val seriesCount = seriesDao.watchCount().first()
                android.util.Log.w("HomeVM", "[DB] movies=${allContent.size} shows=${showContent.size} live=${liveContent.size} series=$seriesCount")
            }

            // Deduplicate quality variants *before* enrichment so we
            // don't waste TMDB calls on hidden duplicates.
            launch(Dispatchers.IO) {
                runCatching { movieDeduplicator.dedup() }
                    .onFailure { Log.w("HomeVM", "Movie dedup failed", it) }
                runCatching { movieDeduplicator.dedupSeries() }
                    .onFailure { Log.w("HomeVM", "Series dedup failed", it) }
            }.join()

            // Switch tracker to enrichment phase.
            enrichmentTracker.startEnrichment(total = 0)
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
                val movies = movieDao.byGenre(genre, limit = 40)
                    .distinctBy { it.movieTitle.lowercase() }
                    .take(20)
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
                val movies = movieDao.byProviderForList(prov.provider, limit = 40)
                    .distinctBy { it.movieTitle.lowercase() } // dedup quality variants
                    .take(20)
                if (movies.size >= 3) {
                    HomeProviderRail("New on ${prov.provider}", movies)
                } else null
            }
            _providerRails.value = rails
        } catch (e: Throwable) {
            Log.w("HomeViewModel", "Provider rail build failed", e)
        }
    }

    /**
     * Enqueue a periodic WorkManager task that syncs the M3U playlist
     * every 12 hours when a network connection is available.
     * Uses [ExistingPeriodicWorkPolicy.KEEP] so re-entering the Home
     * screen doesn't reset the schedule.
     */
    private fun schedulePeriodicSync() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = PeriodicWorkRequestBuilder<SyncWorker>(
            repeatInterval = 12,
            repeatIntervalTimeUnit = TimeUnit.HOURS,
        )
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(application).enqueueUniquePeriodicWork(
            SyncWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
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
