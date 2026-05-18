package com.strata.tv.ui.shows

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.util.Log
import com.strata.tv.data.db.ContentDao
import com.strata.tv.data.db.ContentItemEntity
import com.strata.tv.data.db.ContinueWatchingDao
import com.strata.tv.data.db.ContinueWatchingEntity
import com.strata.tv.data.db.EpisodeDao
import com.strata.tv.data.db.EpisodeEntity
import com.strata.tv.data.db.SeriesDao
import com.strata.tv.data.db.SeriesEntity
import com.strata.tv.data.db.SourceDao
import com.strata.tv.data.db.WatchlistDao
import com.strata.tv.data.db.WatchlistEntity
import com.strata.tv.data.settings.SettingsRepository
import com.strata.tv.data.xtream.XtreamJsonClient
import com.strata.tv.domain.ContentIdHasher
import com.strata.tv.domain.TitleParser
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

/**
 * Drives [ShowDetailScreen].
 *
 * Loads a series by title, streams its episodes grouped by season,
 * and manages watchlist toggle.
 */
@HiltViewModel
class ShowDetailViewModel @Inject constructor(
    private val seriesDao: SeriesDao,
    private val episodeDao: EpisodeDao,
    private val contentDao: ContentDao,
    private val watchlistDao: WatchlistDao,
    private val cwDao: ContinueWatchingDao,
    private val xtreamJson: XtreamJsonClient,
    private val settingsRepo: SettingsRepository,
    private val sourceDao: SourceDao,
) : ViewModel() {

    /** Series IDs that have already had their episodes fetched this process lifetime. */
    private val lazyFetched = mutableSetOf<Int>()

    private val _state = MutableStateFlow<ShowDetailState>(ShowDetailState.Loading)
    val state: StateFlow<ShowDetailState> = _state.asStateFlow()

    private val _selectedSeason = MutableStateFlow(1)
    val selectedSeason: StateFlow<Int> = _selectedSeason.asStateFlow()

    private var _seriesTitle: String = ""

    /**
     * Current load job — cancelled and replaced on each `load()` so a
     * previous series' episode+watchlist flow subscription doesn't leak
     * until the whole ViewModel scope dies (idioms review #3).
     */
    private var loadJob: Job? = null

    fun load(seriesTitle: String) {
        if (_seriesTitle == seriesTitle) return
        _seriesTitle = seriesTitle
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val series = seriesDao.byTitle(seriesTitle)
            if (series == null) {
                _state.value = ShowDetailState.NotFound
                return@launch
            }

            // Acknowledge any "NEW episodes" badge for this series — the
            // user has now visited the detail screen, so the next sync
            // will only flag deltas above the current total.  Fire-and-
            // forget: the UPDATE happens in a separate row write, no
            // need to block the episode flow on it.
            runCatching { seriesDao.markEpisodesSeen(seriesTitle) }

            // Lazy-fetch episodes if this is a Xtream series that hasn't
            // been expanded yet.  The initial sync persists series
            // metadata only — episode lists are pulled on first
            // detail-screen open via player_api.php to keep first-launch
            // sync from hammering rate-limited providers.
            launch { ensureEpisodesLoaded(series) }

            // Observe episodes + watchlist + continue-watching together
            combine(
                episodeDao.watchSeries(seriesTitle),
                watchlistDao.watchIsInWatchlist(seriesTitle),
                cwDao.watchLatestForSeries(seriesTitle),
            ) { episodes, isOnWatchlist, latestCw ->
                val seasons = episodes
                    .groupBy { it.seasonNumber }
                    .toSortedMap()
                val resumeInfo = resolveResumeInfo(latestCw, episodes)
                ShowDetailState.Loaded(
                    series = series,
                    seasons = seasons,
                    isOnWatchlist = isOnWatchlist,
                    resumeInfo = resumeInfo,
                )
            }.collect { _state.value = it }
        }
    }

    /**
     * Given the most recent CW entry for this series, determine what the
     * play button should offer: resume mid-episode, or play the next one.
     */
    private suspend fun resolveResumeInfo(
        cw: ContinueWatchingEntity?,
        episodes: List<EpisodeEntity>,
    ): ResumeInfo? {
        if (cw == null || cw.seasonNumber == null || cw.episodeNumber == null) return null
        val cwEpisode = episodes.find {
            it.seasonNumber == cw.seasonNumber && it.episodeNumber == cw.episodeNumber
        } ?: return null

        val remainingMs = cw.totalDurationMs - cw.resumePositionMs
        val isFinished = cw.totalDurationMs > 0 && remainingMs <= CREDITS_THRESHOLD_MS

        return if (!isFinished) {
            // Still watching this episode — offer to resume.
            ResumeInfo(
                episode = cwEpisode,
                resumePositionMs = cw.resumePositionMs,
            )
        } else {
            // Episode finished — offer the next one.
            val next = episodeDao.nextEpisode(
                cw.seriesTitle!!, cw.seasonNumber, cw.episodeNumber,
            )
            if (next != null) {
                ResumeInfo(episode = next, resumePositionMs = 0L)
            } else {
                null // Series complete, fall back to default.
            }
        }
    }

    /**
     * Fetch and persist episodes for a series whose catalogue was
     * imported from the Xtream JSON API but never expanded via
     * `get_series_info`.  Runs only the first time the user opens the
     * detail screen for a given series this process lifetime — Room
     * caches the episode rows so subsequent opens are instant.
     */
    private suspend fun ensureEpisodesLoaded(series: SeriesEntity) {
        val xtreamId = series.xtreamSeriesId ?: return
        if (xtreamId in lazyFetched) return
        if (episodeDao.countForSeries(series.seriesTitle) > 0) {
            lazyFetched += xtreamId
            return
        }
        val provider = settingsRepo.current().provider
        if (provider.host.isBlank() ||
            provider.username.isBlank() ||
            provider.password.isBlank()
        ) {
            return
        }

        Log.d(TAG, "Lazy-loading episodes for series '${series.seriesTitle}' (xtream id=$xtreamId)")
        val entries = xtreamJson.fetchEpisodesForSeries(
            host = provider.host,
            user = provider.username,
            pass = provider.password,
            seriesId = xtreamId,
            seriesTitle = series.seriesTitle,
            groupTitle = "",
        )
        if (entries.isEmpty()) {
            Log.w(TAG, "No episodes returned for '${series.seriesTitle}'")
            lazyFetched += xtreamId
            return
        }

        val sourceKey = provider.host
        val resolvedSourceId = sourceDao.all().firstOrNull()?.id ?: return
        val contentRows = mutableListOf<ContentItemEntity>()
        val episodeRows = mutableListOf<EpisodeEntity>()
        val normalised = TitleParser.normalise(series.seriesTitle)
        for (entry in entries) {
            val episodeContentId = ContentIdHasher.hash(
                sourceKey = sourceKey,
                normalisedTitle = "$normalised s${entry.seasonNumber}e${entry.episodeNumber}",
                groupTitle = entry.groupTitle,
                streamUrl = entry.streamUrl,
            )
            contentRows += ContentItemEntity(
                contentId = episodeContentId,
                sourceId = resolvedSourceId,
                displayName = entry.displayName,
                streamUrl = entry.streamUrl,
                groupTitle = entry.groupTitle,
                contentType = "show",
                tvgId = entry.tvgId,
                tvgName = entry.tvgName,
                tvgLogo = entry.tvgLogo,
                tvgType = entry.tvgType,
                title = series.seriesTitle,
            )
            episodeRows += EpisodeEntity(
                contentId = episodeContentId,
                seriesTitle = series.seriesTitle,
                seasonNumber = entry.seasonNumber ?: 0,
                episodeNumber = entry.episodeNumber ?: 0,
                streamUrl = entry.streamUrl,
            )
        }
        contentDao.upsertAll(contentRows)
        episodeDao.upsertAll(episodeRows)
        Log.d(TAG, "Persisted ${episodeRows.size} episodes for '${series.seriesTitle}'")
        lazyFetched += xtreamId
    }

    companion object {
        private const val TAG = "ShowDetailViewModel"

        /** If <= 3 minutes remain, treat the episode as finished. */
        private const val CREDITS_THRESHOLD_MS = 180_000L
    }

    fun selectSeason(season: Int) {
        _selectedSeason.value = season
    }

    fun toggleWatchlist() {
        val current = (_state.value as? ShowDetailState.Loaded) ?: return
        viewModelScope.launch {
            if (current.isOnWatchlist) {
                watchlistDao.remove(_seriesTitle)
            } else {
                watchlistDao.add(
                    WatchlistEntity(
                        contentId = _seriesTitle,
                        contentType = "show",
                        title = current.series.seriesTitle,
                        artworkUrl = current.series.posterUrl,
                        addedAt = Instant.now(),
                    ),
                )
            }
        }
    }

    /**
     * Resolve the stream URL for an episode by looking up the content item.
     */
    suspend fun resolveStreamUrl(episode: EpisodeEntity): String {
        return episode.streamUrl.ifBlank {
            contentDao.byContentId(episode.contentId)?.streamUrl ?: ""
        }
    }
}

sealed interface ShowDetailState {
    data object Loading : ShowDetailState
    data object NotFound : ShowDetailState
    data class Loaded(
        val series: SeriesEntity,
        val seasons: Map<Int, List<EpisodeEntity>>,
        val isOnWatchlist: Boolean,
        val resumeInfo: ResumeInfo? = null,
    ) : ShowDetailState
}

/**
 * What the play button should offer.
 *
 * @property episode     the episode to play
 * @property resumePositionMs  >0 means "continue where you left off";
 *                             0 means "play from the start" (next episode)
 */
data class ResumeInfo(
    val episode: EpisodeEntity,
    val resumePositionMs: Long,
)
