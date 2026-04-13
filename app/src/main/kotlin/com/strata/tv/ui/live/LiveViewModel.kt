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
import com.strata.tv.domain.ChannelDeduplicator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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
    private val programmeDao: ProgrammeDao,
    private val epgFetchService: EpgFetchService,
) : ViewModel() {

    companion object {
        private const val TAG = "LiveViewModel"
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

    val state: StateFlow<LiveUiState> = combine(
        _channels,
        _categories,
        _selectedCategory,
    ) { channels, categories, selected ->
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
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = LiveUiState.Empty,
    )

    init {
        // Kick off the EPG fetch (once, only if stale).
        viewModelScope.launch {
            try {
                _epgLoading.value = true
                epgFetchService.fetchIfNeeded()
            } catch (e: Throwable) {
                Log.e(TAG, "EPG fetch failed", e)
            } finally {
                _epgLoading.value = false
            }
            // After fetch (or skip), build the guide.
            refreshGuide()
        }

        // Also react to channel list changes from Room.
        viewModelScope.launch {
            channelDao.watchAll().collect {
                refreshGuide()
            }
        }
    }

    fun selectCategory(category: String) {
        _selectedCategory.value = category
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
            val channels = channelDao.watchAll().stateIn(viewModelScope).value
            val liveContent = contentDao.byType("live")
            val contentByContentId = liveContent.associateBy { it.contentId }

            // Build the EPG matcher.
            val matcher = EpgChannelMatcher.build(programmeDao)

            // Fetch programmes in a 4-hour window from now.
            val now = Instant.now()
            val windowEnd = now.plus(4, ChronoUnit.HOURS)
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
                        nextTitle = nextProg?.title,
                        streamUrl = content.streamUrl,
                    ),
                )
            }

            // Sort by channel number (if available), then by name.
            result.sortWith(compareBy<ChannelWithGuide> {
                it.channelNumber ?: Int.MAX_VALUE
            }.thenBy { it.displayName.lowercase() })

            _channels.value = result
            _categories.value = listOf("All") + categorySet.sorted()
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
    val nextTitle: String?,
    val streamUrl: String,
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
