package com.strata.tv.ui.home

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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Drives [HomeScreen].  Pulls counts + Continue Watching + a small
 * sample of recent movies from Room and exposes them as a single
 * [HomeUiState] StateFlow.
 *
 * Triggers the initial M3U sync on first construction (only when the
 * library is empty).  The full provider rails arrive in a later
 * phase that adds the TMDB enrichment + per-provider queries; for
 * now we just want SOMETHING populating the DB so the home screen
 * isn't permanently full of "Sample 1, 2, 3…".
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

    val state: StateFlow<HomeUiState> = combine(
        movieDao.watchVisibleCount(),
        seriesDao.watchCount(),
        channelDao.watchAll(),
        cwDao.watchAll(),
        movieDao.watchAllForList(),
    ) { movies, series, channels, cw, recentMovies ->
        HomeUiState(
            channelCount = channels.size,
            movieCount = movies,
            seriesCount = series,
            continueWatching = cw,
            recentMovies = recentMovies.take(20),
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HomeUiState.Empty,
    )

    /** Sync progress, surfaced separately so the UI can show a banner. */
    val syncProgress: StateFlow<SyncService.Progress> = syncService.progress

    init {
        viewModelScope.launch {
            val sourceId = bootstrap.ensureSource()
            val firstCount = movieDao.watchVisibleCount().first()
            if (firstCount == 0) {
                runCatching { syncService.syncFromUrl(AppConfig.PLAYLIST_URL, sourceId) }
            }
            // Kick off TMDB enrichment on IO — runs after sync so there
            // are rows to enrich.  Fire-and-forget: failures are logged
            // inside each service and never propagate to the UI.
            enrichmentTracker.reset()
            launch(Dispatchers.IO) { runCatching { movieEnrichment.enrichBatch() } }
            launch(Dispatchers.IO) { runCatching { seriesEnrichment.enrichBatch() } }
        }
    }
}

/**
 * Snapshot of the home screen's view model.
 */
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
