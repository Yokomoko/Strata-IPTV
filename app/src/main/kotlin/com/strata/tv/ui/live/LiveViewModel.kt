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
import kotlinx.coroutines.flow.first
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

    val state: StateFlow<LiveUiState> = combine(
        _channels,
        _categories,
        _selectedCategory,
        _lastRefreshed,
    ) { channels, categories, selected, refreshedAt ->
        val filtered = if (selected == "All") {
            channels
        } else {
            channels.filter { it.category == selected }
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
        // 1. Show channels immediately (no EPG data yet) so the user
        //    doesn't stare at "No channels" while the EPG fetches.
        viewModelScope.launch(Dispatchers.IO) {
            refreshGuide()
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

        // 3. React to channel list changes from Room (e.g. after a sync).
        viewModelScope.launch(Dispatchers.IO) {
            channelDao.watchAll().collect {
                refreshGuide()
            }
        }

        // 4. Periodic EPG now/next refresh (#18) — re-queries programme
        //    data every 5 minutes so the guide stays current without
        //    needing a full channel list rebuild.
        startPeriodicEpgRefresh()
    }

    fun selectCategory(category: String) {
        _selectedCategory.value = category
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
     * [EPG_REFRESH_INTERVAL_MS] milliseconds.
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
     * list.  Does NOT rebuild the full channel list or re-run the
     * EPG matcher — that's the expensive part handled by [refreshGuide].
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
     * Rebuild the full guide: join channels + content items + EPG.
     *
     * This is not a Flow-driven combine because the EPG matcher needs
     * to be built once (suspend) and the programme window is a point-
     * in-time query, not a live Flow.  We re-run this when:
     * - The channel list emits a new value
     * - The EPG fetch completes
     */
    private suspend fun refreshGuide() {
        try {
            val channels = channelDao.watchAll().first()
            val liveContent = contentDao.byType("live")
            val contentByContentId = liveContent.associateBy { it.contentId }

            // Build the EPG matcher, enriched with XMLTV display names
            // when available from the last fetch.
            val matcher = EpgChannelMatcher.build(
                programmeDao = programmeDao,
                xmltvDisplayNames = epgFetchService.lastParseDisplayNames,
            )

            // Fetch programmes in a 4-hour window from now.
            val now = Instant.now()
            val windowEnd = now.plus(NOW_NEXT_WINDOW_HOURS, ChronoUnit.HOURS)
            val programmes = programmeDao.inRange(now, windowEnd)

            // Group programmes by their XMLTV channel id for fast lookup.
            val progByChannel = programmes.groupBy { it.channelId }

            val result = mutableListOf<ChannelWithGuide>()
            val categorySet = mutableSetOf<String>()

            for (channel in channels) {
                val content = contentByContentId[channel.contentId] ?: continue
                val displayName = ChannelDeduplicator.cleanChannelName(content.displayName)

                // Resolve the XMLTV channel id for this channel.
                val xmltvId = matcher.resolve(content)

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

                val category = channel.category.ifBlank { "General" }
                categorySet.add(category)

                result.add(
                    ChannelWithGuide(
                        channelEntity = channel,
                        contentItem = content,
                        displayName = displayName,
                        logoUrl = channel.logoUrl,
                        channelNumber = channel.channelNumber,
                        category = category,
                        nowTitle = nowProg?.title,
                        nowDescription = nowProg?.description,
                        nowStartTime = nowProg?.startTime,
                        nowEndTime = nowProg?.endTime,
                        nextTitle = nextProg?.title,
                        nextStartTime = nextProg?.startTime,
                        nextEndTime = nextProg?.endTime,
                        streamUrl = content.streamUrl,
                        xmltvChannelId = xmltvId,
                    ),
                )
            }

            // Sort by channel number (if available), then by name.
            result.sortWith(compareBy<ChannelWithGuide> {
                it.channelNumber ?: Int.MAX_VALUE
            }.thenBy { it.displayName.lowercase() })

            _channels.value = result
            _lastRefreshed.value = now
            // Sort categories by the defined display order, not alphabetically.
            val order = ChannelCategorizer.displayOrder
            val sorted = categorySet.sortedBy { cat ->
                val idx = order.indexOf(cat)
                if (idx >= 0) idx else order.size // Unknown categories go at the end
            }
            _categories.value = listOf("All") + sorted

            // Log coverage summary.
            val withGuide = result.count { it.nowTitle != null }
            val total = result.size
            Log.i(
                TAG,
                "Guide refresh: $total channels, $withGuide with Now data " +
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
