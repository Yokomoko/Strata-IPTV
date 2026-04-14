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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
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
    ) { channels, categories, selected ->
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
    }

    fun selectCategory(category: String) {
        _selectedCategory.value = category
    }

    /** Toggle the favourite status of a channel by its content ID. */
    fun toggleFavourite(contentId: String, currentlyFavourite: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            channelDao.setFavourite(contentId, !currentlyFavourite)
            // The Room Flow from watchAll() will trigger refreshGuide()
            // automatically, which rebuilds the channel list with the
            // updated isFavourite flag.
        }
    }

    /**
     * Mark a live channel as last-watched with the current timestamp.
     * Called when the user starts playing a live channel.
     */
    fun markChannelWatched(contentId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            channelDao.markWatched(contentId, Instant.now())
            _lastWatchedContentId.value = contentId
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
                        isFavourite = channel.isFavourite,
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

            // Add the Favourites pseudo-category if any channels are favourited.
            val hasFavourites = result.any { it.isFavourite }
            if (hasFavourites) categorySet.add("Favourites")

            // Sort categories by the defined display order, not alphabetically.
            val order = ChannelCategorizer.displayOrder
            val sorted = categorySet.sortedBy { cat ->
                val idx = order.indexOf(cat)
                if (idx >= 0) idx else order.size // Unknown categories go at the end
            }
            _categories.value = listOf("All") + sorted

            // Resolve the last-watched channel on first load.
            if (_lastWatchedContentId.value == null) {
                val lastWatched = result
                    .filter { it.channelEntity.lastWatched != null }
                    .maxByOrNull { it.channelEntity.lastWatched!! }
                _lastWatchedContentId.value = lastWatched?.channelEntity?.contentId
            }

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
