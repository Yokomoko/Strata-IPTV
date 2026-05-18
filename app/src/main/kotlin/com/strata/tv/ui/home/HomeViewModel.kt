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
import com.strata.tv.data.db.ChannelDao
import com.strata.tv.data.db.ContentDao
import com.strata.tv.data.db.ContinueWatchingDao
import com.strata.tv.data.db.ContinueWatchingEntity
import com.strata.tv.data.db.MovieDao
import com.strata.tv.data.db.MovieEntity
import com.strata.tv.data.db.MovieListItem
import com.strata.tv.data.db.SeriesDao
import com.strata.tv.data.db.SeriesEntity
import com.strata.tv.data.db.SourceDao
import com.strata.tv.data.db.WatchlistDao
import com.strata.tv.data.db.WatchlistEntity
import com.strata.tv.data.repo.BootstrapRepository
import com.strata.tv.data.repo.SyncService
import com.strata.tv.data.repo.SyncWorker
import com.strata.tv.data.settings.SettingsRepository
import com.strata.tv.data.tmdb.EnrichmentProgressTracker
import com.strata.tv.data.tmdb.MovieEnrichmentService
import com.strata.tv.data.tmdb.SeriesEnrichmentService
import com.strata.tv.domain.MovieDeduplicator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.sample
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
    private val settingsRepo: SettingsRepository,
) : ViewModel() {

    // -- Core state (lightweight flows) --------------------------------

    /**
     * Home state assembled from six upstream flows.
     *
     * Uses two three-arity `combine` stages instead of the variadic
     * array overload so every cast is compiler-checked.  The six-arity
     * variant required `@Suppress("UNCHECKED_CAST")` + manual index
     * casts, where any reordering silently produced a ClassCastException
     * at runtime (idioms review #1 — crash risk).
     *
     * `channelDao.watchCount()` replaces `watchAll()` here so the
     * whole channel list isn't materialised just to take `.size`
     * (perf review #6).
     */
    private val counts: Flow<Triple<Int, Int, Int>> = combine(
        movieDao.watchVisibleCount(),
        seriesDao.watchCount(),
        channelDao.watchCount(),
    ) { movies, series, channels ->
        Triple(movies, series, channels)
    }

    private data class HomeLists(
        val continueWatching: List<ContinueWatchingEntity>,
        val recentMovies: List<MovieListItem>,
        val heroCandidates: List<MovieEntity>,
    )

    private val lists: Flow<HomeLists> = combine(
        // watchAllGrouped() collapses show episodes by series (Netflix-
        // style "one row per series, most recent episode wins") while
        // leaving movies + live channels untouched.  See
        // [ContinueWatchingDao.watchAllGrouped] for the SQL.
        cwDao.watchAllGrouped(),
        movieDao.watchRecentWithPosters(limit = 20),
        movieDao.watchHeroCandidates(limit = 5),
    ) { cw, recent, heroes ->
        HomeLists(cw, recent, heroes)
    }

    /**
     * Series that have grown in episode count since the user last
     * engaged with them AND are either on the watchlist or have a
     * continue-watching entry.  Drives the "New Episodes" rail and the
     * NEW badge across show poster cards.
     */
    val newEpisodeShows: StateFlow<List<SeriesEntity>> =
        seriesDao.watchNewEpisodeShows(limit = 20)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val state: StateFlow<HomeUiState> = combine(counts, lists) { (movies, series, channels), l ->
        HomeUiState(
            channelCount = channels,
            movieCount = movies,
            seriesCount = series,
            continueWatching = l.continueWatching,
            recentMovies = l.recentMovies,
            heroCandidates = l.heroCandidates,
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
        Log.i(TAG, "HomeViewModel init")
        // Schedule periodic background sync via WorkManager (every 12h,
        // network-required).  This replaces the old forced-every-launch
        // sync and keeps the library fresh without blocking app start.
        schedulePeriodicSync()

        viewModelScope.launch {
            Log.i(TAG, "Waiting for provider config...")
            // Suspend until the provider is configured.  HomeViewModel
            // is now instantiated at the top of Shell (so its init fires
            // early), but the user is usually still in the wizard at
            // that point.  Waiting on the settings Flow means the sync
            // kicks off the instant the wizard saves credentials.
            val settings = settingsRepo.settings
                .filter { it.provider.isConfigured }
                .first()
            Log.i(TAG, "Provider configured: ${settings.provider.providerId}, " +
                "host=${settings.provider.host}, starting sync flow")

            val sourceId = bootstrap.ensureSource()
            enrichmentTracker.startSync()

            // Sync if the DB is empty OR the configured frequency says so.
            val movieCount = movieDao.watchVisibleCount().first()
            val today = Instant.now().epochSecond / 86_400
            val lastSyncDay = settingsRepo.lastSyncEpochDay()
            val needsSync = movieCount == 0 ||
                settings.syncFrequency.shouldSync(lastSyncDay, today)
            Log.i(TAG, "needsSync=$needsSync (movieCount=$movieCount, " +
                "freq=${settings.syncFrequency}, lastSyncDay=$lastSyncDay, today=$today)")
            if (needsSync) {
                val url = settings.provider.toM3uUrl()
                Log.i(TAG, "Calling syncService.syncFromUrl($url, sourceId=$sourceId)")
                runCatching {
                    syncService.syncFromUrl(url, sourceId)
                }
                    .onSuccess {
                        Log.i(TAG, "Sync completed successfully")
                        runCatching { sourceDao.markSynced(sourceId, Instant.now()) }
                        runCatching { settingsRepo.setLastSyncEpochDay(today) }
                    }
                    .onFailure { e ->
                        Log.e(TAG, "Sync failed", e)
                    }
            } else {
                // Mark the sync pipeline as already-done so the gate
                // that waits for SyncService.Progress.Done can transition
                // to Main; otherwise a returning user (sync frequency
                // cap not yet reached) would be stuck on the FirstSync
                // progress screen forever.
                syncService.markSkipped()
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

        // Rebuild genre + provider rails reactively off enrichment
        // progress instead of the old 5s/30s/90s delay schedule
        // (architect review #7, idioms #2, quality #7 — issue #41).
        //
        // sample() emits the latest progress value at most once per
        // window.  Once the tracker quiesces (no state changes after
        // finish()), StateFlow stops emitting and sample stops firing,
        // so this collector naturally goes idle — no explicit
        // wasRunning bookkeeping needed.
        viewModelScope.launch(Dispatchers.IO) {
            // Immediate first render from whatever is already in Room.
            buildGenreRails()
            buildProviderRails()

            enrichmentTracker.progress
                .sample(RAIL_REBUILD_THROTTLE_MS)
                .collect {
                    buildGenreRails()
                    buildProviderRails()
                }
        }
    }

    companion object {
        private const val TAG = "HomeVM"

        /**
         * Cap how often the reactive rail rebuild can fire.  Enrichment
         * calls `advance()` after every TMDB match, so `progress` can
         * emit hundreds of times per minute; without a throttle the
         * rails would regroup on every emission.
         */
        private const val RAIL_REBUILD_THROTTLE_MS = 8_000L
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
