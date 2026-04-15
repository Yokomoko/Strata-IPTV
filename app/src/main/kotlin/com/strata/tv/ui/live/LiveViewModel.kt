package com.strata.tv.ui.live

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.strata.tv.data.db.ChannelDao
import com.strata.tv.data.db.ChannelEntity
import com.strata.tv.data.db.ContentDao
import com.strata.tv.data.db.ContentItemEntity
import com.strata.tv.data.db.ProgrammeDao
import com.strata.tv.data.db.ProgrammeEntity
import com.strata.tv.data.epg.EpgChannelMatcher
import com.strata.tv.data.repo.EpgFetchService
import com.strata.tv.domain.ChannelCategorizer
import com.strata.tv.domain.ChannelDeduplicator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.inject.Inject

/**
 * Drives [LiveScreen] — the 1D TV Guide.
 *
 * Combines:
 * - [ChannelDao.watchAll] for the channel list
 * - [ContentDao.byType] for display names (joined in-memory by contentId)
 * - [ProgrammeDao.inRange] for now/next programme data
 * - [EpgChannelMatcher] to bridge M3U channel ids to XMLTV ids
 *
 * EPG fetch is triggered once on init (only if data is stale).
 */
@HiltViewModel
class LiveViewModel @Inject constructor(
    private val channelDao: ChannelDao,
    private val contentDao: ContentDao,
    internal val programmeDao: ProgrammeDao,
    private val epgFetchService: EpgFetchService,
) : ViewModel() {

    companion object {
        private const val TAG = "LiveViewModel"

        /**
         * Programme query window for the Now/Next display.
         * A 4-hour window is enough for the 1D guide rows.
         */
        private const val NOW_NEXT_WINDOW_HOURS = 4L

        /**
         * Programme query window for the full EPG grid view.
         * 12 hours provides a meaningful timeline for browsing.
         */
        const val GRID_WINDOW_HOURS = 12L

        /**
         * How often the now/next EPG data is automatically refreshed.
         * 5 minutes keeps the guide current without hammering the DB.
         */
        private const val EPG_REFRESH_INTERVAL_MS = 5 * 60 * 1000L
    }

    private val _epgLoading = MutableStateFlow(false)
    val epgLoading: StateFlow<Boolean> = _epgLoading.asStateFlow()

    /** The resolved channel list with now/next, driven by Room flows. */
    private val _channels = MutableStateFlow<List<ChannelWithGuide>>(emptyList())

    /** Available category filters, rebuilt when the channel list changes. */
    private val _categories = MutableStateFlow<List<String>>(listOf("All"))

    /** Currently selected category chip. */
    private val _selectedCategory = MutableStateFlow("All")
    val selectedCategory: StateFlow<String> = _selectedCategory.asStateFlow()

    /** Timestamp of the most recent now/next EPG refresh (#18). */
    private val _lastRefreshed = MutableStateFlow<Instant?>(null)
    val lastRefreshed: StateFlow<Instant?> = _lastRefreshed.asStateFlow()

    /** Background job for the periodic EPG refresh timer. */
    private var epgRefreshJob: Job? = null

    /**
     * The content ID of the most recently watched live channel.
     * Used by the UI to auto-scroll to the last-watched row.
     */
    private val _lastWatchedContentId = MutableStateFlow<String?>(null)
    val lastWatchedContentId: StateFlow<String?> = _lastWatchedContentId.asStateFlow()

    val state: StateFlow<LiveUiState> = combine(
        _channels,
        _categories,
        _selectedCategory,
        _lastRefreshed,
    ) { channels, categories, selected, refreshedAt ->
        val filtered = when (selected) {
            "All" -> channels
            "Favourites" -> channels.filter { it.isFavourite }
            else -> channels.filter { it.category == selected }
        }
        LiveUiState(
            channels = filtered,
            categories = categories,
            selectedCategory = selected,
            isLoading = false,
            lastRefreshed = refreshedAt,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = LiveUiState.Empty,
    )

    init {
        // 1. React to channel list changes from Room.  Room Flows emit
        //    their current value on subscription so this both fills the
        //    initial guide AND handles post-sync refreshes.
        //
        //    distinctUntilChanged keyed on the contentId set so that
        //    single-row writes (markWatched, setFavourite) don't cause
        //    a full guide rebuild — Phase-2 now/next refresh handles
        //    those cases on its own timer.
        viewModelScope.launch(Dispatchers.IO) {
            channelDao.watchAll()
                .map { channels -> channels.map { it.contentId }.toSet() }
                .distinctUntilChanged()
                .collect {
                    refreshGuide()
                }
        }

        // 2. EPG fetch in parallel — when done, refresh to overlay
        //    now/next programme data onto the already-visible channels.
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _epgLoading.value = true
                epgFetchService.fetchIfNeeded()
            } catch (e: Throwable) {
                Log.e(TAG, "EPG fetch failed", e)
            } finally {
                _epgLoading.value = false
            }
            refreshGuide()
        }

        // 3. Periodic EPG now/next refresh (#18) — re-queries programme
        //    data every 5 minutes so the guide stays current without
        //    needing a full channel list rebuild.
        startPeriodicEpgRefresh()
    }

    fun selectCategory(category: String) {
        _selectedCategory.value = category
    }

    /** Toggle the favourite status of a channel by its content ID. */
    fun toggleFavourite(contentId: String, currentlyFavourite: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                channelDao.setFavourite(contentId, !currentlyFavourite)
            }.onFailure { Log.e(TAG, "Failed to toggle favourite: $contentId", it) }
        }
    }

    /**
     * Mark a live channel as last-watched with the current timestamp.
     * Called when the user starts playing a live channel.
     */
    fun markChannelWatched(contentId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                channelDao.markWatched(contentId, Instant.now())
                _lastWatchedContentId.value = contentId
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to mark channel as watched: $contentId", e)
            }
        }
    }

    /**
     * Call when the user re-enters the Live tab (e.g. from the player
     * or another destination).  Triggers an immediate lightweight
     * now/next refresh so the guide is never visibly stale (#18).
     */
    fun refreshOnResume() {
        if (_channels.value.isEmpty()) return          // let init populate first
        viewModelScope.launch(Dispatchers.IO) {
            refreshNowNext()
        }
    }

    /**
     * Starts a background coroutine that refreshes now/next data every
     * [EPG_REFRESH_INTERVAL_MS] milliseconds (#18).
     */
    private fun startPeriodicEpgRefresh() {
        epgRefreshJob?.cancel()
        epgRefreshJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(EPG_REFRESH_INTERVAL_MS)
                Log.d(TAG, "Periodic EPG refresh triggered")
                refreshNowNext()
            }
        }
    }

    /**
     * Lightweight now/next refresh: re-queries [ProgrammeDao.inRange]
     * with a fresh `Instant.now()` and re-maps the existing channel
     * list.  Does NOT rebuild the channel list or re-run the EPG
     * matcher — that's the expensive part handled by [refreshGuide].
     */
    private suspend fun refreshNowNext() {
        try {
            if (_channels.value.isEmpty()) {
                // No channels loaded yet — fall back to full refresh.
                refreshGuide()
                return
            }

            val now = Instant.now()
            val windowEnd = now.plus(NOW_NEXT_WINDOW_HOURS, ChronoUnit.HOURS)
            val programmes = programmeDao.inRange(now, windowEnd)
            val progByChannel = programmes.groupBy { it.channelId }

            // Use update{} for atomic read-modify-write so a concurrent
            // refreshGuide() result is never accidentally overwritten.
            _channels.update { current ->
                current.map { ch ->
                    val channelProgs = if (ch.xmltvChannelId != null) {
                        progByChannel[ch.xmltvChannelId] ?: emptyList()
                    } else {
                        emptyList()
                    }

                    val nowProg = channelProgs
                        .firstOrNull { it.startTime <= now && it.endTime > now }
                    val nextProg = channelProgs
                        .filter { it.startTime > now }
                        .minByOrNull { it.startTime }

                    ch.copy(
                        nowTitle = nowProg?.title,
                        nowDescription = nowProg?.description,
                        nowStartTime = nowProg?.startTime,
                        nowEndTime = nowProg?.endTime,
                        nextTitle = nextProg?.title,
                        nextStartTime = nextProg?.startTime,
                        nextEndTime = nextProg?.endTime,
                    )
                }
            }
            _lastRefreshed.value = now

            val channels = _channels.value
            val withGuide = channels.count { it.nowTitle != null }
            Log.d(
                TAG,
                "Now/next refresh: ${channels.size} channels, $withGuide with guide data",
            )
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to refresh now/next", e)
        }
    }

    /**
     * Rebuild the guide in two phases so the channel list appears instantly:
     *
     * **Phase 1 (instant):** load channels + content items, re-derive
     * categories via [ChannelCategorizer], and emit the list with null
     * now/next so the UI renders immediately.
     *
     * **Phase 2 (background):** build the EPG matcher, query the
     * programme window, and overlay now/next onto the existing entries.
     */
    private suspend fun refreshGuide() {
        try {
            // ── Phase 1: channels + content (instant) ───────────────
            val channels = channelDao.watchAll().first()
            val liveContent = contentDao.byType("live")
            val contentByContentId = liveContent.associateBy { it.contentId }

            val phase1 = mutableListOf<ChannelWithGuide>()
            val categorySet = mutableSetOf<String>()

            for (channel in channels) {
                val content = contentByContentId[channel.contentId] ?: continue
                val displayName = ChannelDeduplicator.cleanChannelName(content.displayName)

                // Re-derive category on-the-fly from the current categoriser
                // rules so updated categories apply without a full re-sync.
                val category = ChannelCategorizer.categorise(
                    content.displayName,
                    content.groupTitle,
                ).ifBlank { "General" }
                categorySet.add(category)

                phase1.add(
                    ChannelWithGuide(
                        channelEntity = channel,
                        contentItem = content,
                        displayName = displayName,
                        logoUrl = channel.logoUrl,
                        channelNumber = channel.channelNumber,
                        category = category,
                        isFavourite = channel.isFavourite,
                        nowTitle = null,
                        nowDescription = null,
                        nowStartTime = null,
                        nowEndTime = null,
                        nextTitle = null,
                        nextStartTime = null,
                        nextEndTime = null,
                        streamUrl = content.streamUrl,
                        xmltvChannelId = null,
                    ),
                )
            }

            // Sort by channel number (if available), then by name.
            phase1.sortWith(compareBy<ChannelWithGuide> {
                it.channelNumber ?: Int.MAX_VALUE
            }.thenBy { it.displayName.lowercase() })

            _channels.value = phase1

            // Add Favourites pseudo-category if any channels are favourited.
            if (phase1.any { it.isFavourite }) categorySet.add("Favourites")

            // Sort categories by the defined display order.
            val order = ChannelCategorizer.displayOrder
            val sorted = categorySet.sortedBy { cat ->
                val idx = order.indexOf(cat)
                if (idx >= 0) idx else order.size
            }
            val finalCategories = listOf("All") + sorted
            _categories.value = finalCategories

            // Reset selected category if it's no longer valid.
            if (_selectedCategory.value !in finalCategories) {
                _selectedCategory.value = "All"
            }

            Log.i(TAG, "Phase 1: ${phase1.size} channels rendered (no EPG yet)")

            // ── Phase 2: overlay EPG now/next ───────────────────────
            val matcher = EpgChannelMatcher.build(
                programmeDao = programmeDao,
                xmltvDisplayNames = epgFetchService.lastParseDisplayNames,
            )

            val now = Instant.now()
            val windowEnd = now.plus(NOW_NEXT_WINDOW_HOURS, ChronoUnit.HOURS)
            val programmes = programmeDao.inRange(now, windowEnd)
            val progByChannel = programmes.groupBy { it.channelId }

            val phase2 = phase1.map { entry ->
                val xmltvId = matcher.resolve(entry.contentItem)
                val channelProgs = if (xmltvId != null) {
                    progByChannel[xmltvId] ?: emptyList()
                } else {
                    emptyList()
                }

                val nowProg = channelProgs
                    .firstOrNull { it.startTime <= now && it.endTime > now }
                val nextProg = channelProgs
                    .filter { it.startTime > now }
                    .minByOrNull { it.startTime }

                entry.copy(
                    nowTitle = nowProg?.title,
                    nowDescription = nowProg?.description,
                    nowStartTime = nowProg?.startTime,
                    nowEndTime = nowProg?.endTime,
                    nextTitle = nextProg?.title,
                    nextStartTime = nextProg?.startTime,
                    nextEndTime = nextProg?.endTime,
                    xmltvChannelId = xmltvId,
                )
            }

            _channels.value = phase2
            _lastRefreshed.value = now

            // Resolve the last-watched channel on first load.
            if (_lastWatchedContentId.value == null) {
                val lastWatched = phase2
                    .filter { it.channelEntity.lastWatched != null }
                    .maxByOrNull { it.channelEntity.lastWatched!! }
                _lastWatchedContentId.value = lastWatched?.channelEntity?.contentId
            }

            val withGuide = phase2.count { it.nowTitle != null }
            val total = phase2.size
            Log.i(
                TAG,
                "Phase 2: $total channels, $withGuide with Now data " +
                    "(${if (total > 0) (withGuide * 100 / total) else 0}% coverage). " +
                    "XMLTV has ${matcher.xmltvChannelCount} channels. " +
                    "Matcher: ${matcher.coverageSummary()}",
            )
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to refresh guide", e)
        }
    }
}

// -------------------------------------------------------------------------
// UI state models
// -------------------------------------------------------------------------

/**
 * A channel row in the TV Guide — channel info joined with its
 * now/next programme data.
 */
data class ChannelWithGuide(
    val channelEntity: ChannelEntity,
    val contentItem: ContentItemEntity,
    val displayName: String,
    val logoUrl: String,
    val channelNumber: Int?,
    val category: String,
    val isFavourite: Boolean = false,
    val nowTitle: String?,
    val nowDescription: String?,
    val nowStartTime: Instant? = null,
    val nowEndTime: Instant? = null,
    val nextTitle: String?,
    val nextStartTime: Instant? = null,
    val nextEndTime: Instant? = null,
    val streamUrl: String,
    /** The resolved XMLTV channel id, if matched. Used by the grid view. */
    val xmltvChannelId: String? = null,
)

data class LiveUiState(
    val channels: List<ChannelWithGuide>,
    val categories: List<String>,
    val selectedCategory: String,
    val isLoading: Boolean,
    /** When the now/next EPG data was last refreshed (#18). */
    val lastRefreshed: Instant? = null,
) {
    companion object {
        val Empty = LiveUiState(
            channels = emptyList(),
            categories = listOf("All"),
            selectedCategory = "All",
            isLoading = true,
        )
    }
}
