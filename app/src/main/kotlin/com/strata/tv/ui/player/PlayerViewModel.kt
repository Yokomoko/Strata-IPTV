package com.strata.tv.ui.player

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import com.strata.tv.data.db.ChannelDao
import com.strata.tv.data.db.ContentDao
import com.strata.tv.data.db.ContinueWatchingDao
import com.strata.tv.data.db.ContinueWatchingEntity
import com.strata.tv.data.db.EpisodeDao
import com.strata.tv.data.db.EpisodeEntity
import com.strata.tv.data.db.WatchHistoryDao
import com.strata.tv.data.db.WatchHistoryEntity
import com.strata.tv.domain.ChannelDeduplicator
import com.strata.tv.ui.nav.ChannelPlayInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

/**
 * ViewModel that owns the [ExoPlayer] instance and manages continue-watching
 * persistence for the player screen.
 *
 * The player is created eagerly on construction; the screen composable
 * must call [initialize] once with the stream parameters.  The player
 * is released in [onCleared], which fires when the nav back-stack drops
 * this destination.
 *
 * Periodic saves use the DAO's *silent* variant so Room's Flow watchers
 * are not re-emitted every 30 seconds during playback.  Full
 * notification-emitting saves happen on exit and on pause-to-play
 * transitions so the home screen's Continue Watching rail refreshes at
 * natural navigation boundaries.
 *
 * **Fav mode** (#11): when watching live TV, pressing the Menu button
 * toggles "favourite channel zapping".  In fav mode, D-pad Up/Down
 * cycles through only favourite channels (Sky-style), and a "FAV"
 * badge appears on the mini overlay.
 */
@HiltViewModel
class PlayerViewModel @Inject constructor(
    application: Application,
    private val cwDao: ContinueWatchingDao,
    private val historyDao: WatchHistoryDao,
    private val channelDao: ChannelDao,
    private val episodeDao: EpisodeDao,
    private val contentDao: ContentDao,
) : AndroidViewModel(application) {

    // ── ExoPlayer ────────────────────────────────────────────────────

    /**
     * Fire-Stick-tuned [DefaultLoadControl]:
     *
     * Media3 defaults target desktop-class hardware (50 s min/max buffer,
     * 2.5 s for playback, 5 s for playback after rebuffer).  Holding ~50 s
     * of decoded video competes with Compose's layer cache, Coil's image
     * cache, and Room's cursor buffers on a Fire Stick with ~512 MB app
     * heap — one of the likely contributors to the occasional crashes
     * the user has been seeing.
     *
     * New budget: 15 s target buffer, 8 s minimum, 1.5 s for playback
     * start, 2.5 s after rebuffer.  Live streams genuinely cannot be
     * buffered ahead anyway, so this is free savings there.
     */
    private val loadControl: LoadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            /* minBufferMs = */ 8_000,
            /* maxBufferMs = */ 15_000,
            /* bufferForPlaybackMs = */ 1_500,
            /* bufferForPlaybackAfterRebufferMs = */ 2_500,
        )
        .setPrioritizeTimeOverSizeThresholds(true)
        .build()

    val player: ExoPlayer = ExoPlayer.Builder(application)
        .setLoadControl(loadControl)
        .build()

    // ── UI state ─────────────────────────────────────────────────────

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    // ── Episode list overlay state ──────────────────────────────────
    /**
     * Tracks the current series title for the episode-list overlay.
     * Drives the [episodes] Flow below via flatMapLatest so the overlay
     * always shows the *current* series' episode list, even after
     * next-episode autoplay swaps the active stream.
     */
    private val _seriesTitleFlow = MutableStateFlow<String?>(null)

    /**
     * Reactive episode list for the currently-playing show.  Empty when
     * the active stream is a movie or live channel.  The screen
     * observes this to populate the episode-list overlay.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val episodes: StateFlow<List<EpisodeEntity>> = _seriesTitleFlow
        .flatMapLatest { title ->
            if (title.isNullOrBlank()) flowOf(emptyList())
            else episodeDao.watchSeries(title)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    // ── Internal bookkeeping ─────────────────────────────────────────

    private var initialized = false
    private var resumeApplied = false
    private var saveJob: Job? = null

    // Stream parameters — set once via [initialize].
    private var streamUrl: String = ""
    private var title: String = ""
    private var isLive: Boolean = false
    private var resumePositionMs: Long = 0L
    private var contentType: String = ""
    private var artworkUrl: String = ""

    // ── Series context for next-episode autoplay ────────────────────
    private var seriesTitle: String? = null
    private var seasonNumber: Int? = null
    private var episodeNumber: Int? = null
    private var countdownJob: Job? = null

    // ── Channel switching (live) ────────────────────────────────────
    private var channelList: List<ChannelPlayInfo> = emptyList()
    private var currentChannelIndex: Int = 0
    private var overlayHideJob: Job? = null

    // ── Error retry ─────────────────────────────────────────────────
    private var retryCount = 0
    private var retryJob: Job? = null
    private val maxRetries = 5

    // ── Subtitle tracks ─────────────────────────────────────────────
    private val _subtitleTracks = MutableStateFlow<List<SubtitleTrack>>(emptyList())
    val subtitleTracks: StateFlow<List<SubtitleTrack>> = _subtitleTracks.asStateFlow()

    // ── Fav mode state (#11) ─────────────────────────────────────────

    /**
     * Resolved favourite channel list, loaded once from [ChannelDao.watchFavourites].
     * Each pair is (displayName, streamUrl) matching the channel content.
     */
    private var favouriteChannels: List<FavChannel> = emptyList()

    /** One-shot event to inform the Shell/nav layer that a channel switch happened. */
    data class ChannelSwitchEvent(val streamUrl: String, val title: String, val artworkUrl: String)
    private val _channelSwitchEvent = MutableSharedFlow<ChannelSwitchEvent>(extraBufferCapacity = 1)
    val channelSwitchEvent: SharedFlow<ChannelSwitchEvent> = _channelSwitchEvent.asSharedFlow()

    // ── Player listener ──────────────────────────────────────────────

    private val playerListener = object : Player.Listener {

        override fun onPlaybackStateChanged(playbackState: Int) {
            val buffering = playbackState == Player.STATE_BUFFERING
            val ended = playbackState == Player.STATE_ENDED

            _uiState.update { it.copy(isBuffering = buffering) }

            // Clear error overlay when the stream successfully recovers.
            if (playbackState == Player.STATE_READY && _uiState.value.errorMessage != null) {
                retryCount = 0
                _uiState.update { it.copy(errorMessage = null) }
            }

            // Resume seek: apply once when the player first reaches READY.
            if (playbackState == Player.STATE_READY && !resumeApplied) {
                resumeApplied = true
                if (!isLive && resumePositionMs > 0) {
                    val duration = player.duration
                    val clamped = if (duration > 0 && resumePositionMs > duration) {
                        (duration - 5_000).coerceAtLeast(0)
                    } else {
                        resumePositionMs
                    }
                    player.seekTo(clamped)
                }
            }

            if (ended) {
                saveFullContinueWatching()
                maybeStartAutoplay()
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            val wasPlaying = _uiState.value.isPlaying
            _uiState.update { it.copy(isPlaying = isPlaying) }

            // Pause -> play transition: full save so home rail refreshes.
            if (isPlaying && !wasPlaying && resumeApplied) {
                saveFullContinueWatching()
            }
            // Play -> pause: full save to capture exact position.
            if (!isPlaying && wasPlaying) {
                saveFullContinueWatching()
            }

            // Pause-button-hide bug fix: when playback state changes
            // (e.g. user pressed pause), reset the auto-hide timer so the
            // controls reliably fade after 4 s of inactivity.  Previously
            // only the key handler called showControls(), which started a
            // timer keyed to the moment of the key press — but the
            // resulting recompositions / focus side-effects could keep
            // the controls pinned visible.  Re-arming the timer here on
            // the *actual* play/pause edge guarantees a hide attempt.
            if (wasPlaying != isPlaying && _uiState.value.controlsVisible) {
                restartHideTimer()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            if (retryCount < maxRetries) {
                retryCount++
                val delayMs = (1000L * (1 shl (retryCount - 1).coerceAtMost(4)))
                    .coerceAtMost(30_000L)
                _uiState.update {
                    it.copy(errorMessage = "Retrying... (attempt $retryCount/$maxRetries)")
                }
                retryJob?.cancel()  // cancel any pending retry to prevent stacking
                retryJob = viewModelScope.launch {
                    delay(delayMs)
                    player.prepare()
                }
            } else {
                _uiState.update {
                    it.copy(errorMessage = error.localizedMessage ?: "Stream unavailable after $maxRetries retries")
                }
            }
        }

        override fun onTracksChanged(tracks: Tracks) {
            // Update available subtitle tracks when the stream's tracks change.
            val subs = mutableListOf<SubtitleTrack>()
            for (group in tracks.groups) {
                if (group.type != C.TRACK_TYPE_TEXT) continue
                for (i in 0 until group.length) {
                    val format = group.getTrackFormat(i)
                    val label = format.label
                        ?: format.language?.uppercase()
                        ?: "Track ${subs.size + 1}"
                    subs.add(SubtitleTrack(
                        groupIndex = tracks.groups.indexOf(group),
                        trackIndex = i,
                        label = label,
                        isSelected = group.isTrackSelected(i),
                    ))
                }
            }
            _subtitleTracks.value = subs
            _uiState.update { it.copy(subtitlesEnabled = subs.any { t -> t.isSelected }) }
        }
    }

    // ── Public API ───────────────────────────────────────────────────

    /**
     * Called from the composable after (re)composition to hand in the
     * stream parameters and start playback.
     *
     * The ViewModel is Activity-scoped — it outlives any single player
     * session — so this must handle *subsequent* calls with a different
     * URL by swapping the media item instead of silently ignoring them.
     * Previously a `if (initialized) return` guard meant opening channel
     * B after channel A kept playing channel A's stream.
     */
    fun initialize(
        streamUrl: String,
        title: String,
        isLive: Boolean,
        resumePositionMs: Long,
        contentType: String,
        artworkUrl: String,
        seriesTitle: String? = null,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
    ) {
        // No-op if the caller is re-asserting the same stream we're
        // already set up for (e.g. a recomposition that doesn't change
        // the nav args).
        if (initialized && this.streamUrl == streamUrl) return

        val firstTime = !initialized

        this.streamUrl = streamUrl
        this.title = title
        this.isLive = isLive
        this.resumePositionMs = resumePositionMs
        this.contentType = contentType
        this.artworkUrl = artworkUrl
        this.seriesTitle = seriesTitle
        this.seasonNumber = seasonNumber
        this.episodeNumber = episodeNumber

        // Cancel any pending autoplay from the previous episode.
        cancelAutoplay()

        // Reset per-stream state.
        resumeApplied = false
        retryCount = 0
        retryJob?.cancel()
        _uiState.update { it.copy(isBuffering = true, errorMessage = null) }

        if (firstTime) {
            initialized = true
            player.addListener(playerListener)
            startPeriodicSave()
        }

        player.setMediaItem(MediaItem.fromUri(streamUrl))
        player.playWhenReady = true
        player.prepare()

        // Series context drives the episode-list overlay's data source.
        _seriesTitleFlow.value = if (contentType == "show") seriesTitle else null

        // Expose current season/episode so the overlay's highlight
        // reflects the active stream (not just the navigation args).
        _uiState.update {
            it.copy(
                controlsVisible = true,
                currentSeasonNumber = seasonNumber,
                currentEpisodeNumber = episodeNumber,
            )
        }
        // Make sure the initial controls fade out after 4 s — otherwise
        // PlayerUiState's default controlsVisible=true would stick
        // because nothing else schedules the hide timer.
        restartHideTimer()
    }

    fun togglePlayPause() {
        if (player.isPlaying) {
            player.pause()
        } else {
            player.play()
        }
    }

    fun seekRelative(deltaMs: Long) {
        if (isLive) return
        val current = player.currentPosition
        val duration = player.duration.coerceAtLeast(0)
        val target = (current + deltaMs).coerceIn(0, duration)
        player.seekTo(target)
    }

    // ── Error retry API ─────────────────────────────────────────────

    /** Manual retry — resets the count and immediately re-prepares. */
    fun retryNow() {
        retryJob?.cancel()
        retryCount = 0
        _uiState.update { it.copy(errorMessage = null, isBuffering = true) }
        player.prepare()
    }

    // ── Subtitle API ────────────────────────────────────────────────

    /** Select a specific subtitle track by its group and track index. */
    fun selectSubtitleTrack(groupIndex: Int, trackIndex: Int) {
        val group = player.currentTracks.groups.getOrNull(groupIndex) ?: return
        val override = TrackSelectionOverride(group.mediaTrackGroup, trackIndex)
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setOverrideForType(override)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .build()
        _uiState.update { it.copy(subtitlesEnabled = true) }
    }

    /** Disable all subtitle tracks. */
    fun disableSubtitles() {
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
        _uiState.update { it.copy(subtitlesEnabled = false) }
    }

    /**
     * Set the live channel list for D-pad channel switching.
     * Called once from the composable after it receives the player args.
     */
    fun setChannelList(channels: List<ChannelPlayInfo>, index: Int) {
        channelList = channels
        currentChannelIndex = index.coerceIn(0, (channels.size - 1).coerceAtLeast(0))
        _uiState.update { it.copy(currentChannelIndex = currentChannelIndex) }
    }

    /**
     * Switch to the next (+1) or previous (-1) channel in the list.
     * Wraps around at both ends.  Returns the new [ChannelPlayInfo]
     * so the screen can update its displayed title, or null if the
     * list is empty / not in live mode.
     */
    fun switchChannel(delta: Int): ChannelPlayInfo? {
        if (channelList.isEmpty() || !isLive) return null
        val newIndex = (currentChannelIndex + delta).mod(channelList.size)
        currentChannelIndex = newIndex
        _uiState.update { it.copy(currentChannelIndex = newIndex) }
        val channel = channelList[newIndex]

        // Re-initialize the player with the new stream.
        initialize(
            streamUrl = channel.streamUrl,
            title = channel.displayName,
            isLive = true,
            resumePositionMs = 0L,
            contentType = "live",
            artworkUrl = channel.logoUrl,
        )

        // Show channel overlay.
        showChannelOverlay()

        return channel
    }

    /** Current channel info for the overlay, or null if not in live/channel-list mode. */
    fun currentChannel(): ChannelPlayInfo? {
        if (channelList.isEmpty()) return null
        return channelList.getOrNull(currentChannelIndex)
    }

    // ── Channel overlay visibility ──────────────────────────────────

    fun showChannelOverlay() {
        _uiState.update { it.copy(channelOverlayVisible = true) }
        overlayHideJob?.cancel()
        overlayHideJob = viewModelScope.launch {
            delay(4_000)
            _uiState.update { it.copy(channelOverlayVisible = false) }
        }
    }

    fun hideChannelOverlay() {
        overlayHideJob?.cancel()
        _uiState.update { it.copy(channelOverlayVisible = false) }
    }

    // ── Episode list overlay ────────────────────────────────────────

    /**
     * Toggle the episode-list overlay.  Only meaningful when the
     * current stream is a show (i.e. [seriesTitle] is non-null and
     * [contentType] == "show").  Showing the overlay forces the
     * player controls visible — and the overlay itself is dismissed
     * via [hideEpisodeOverlay] or by pressing Back.
     */
    fun toggleEpisodeOverlay() {
        if (seriesTitle.isNullOrBlank() || contentType != "show") return
        val nowVisible = !_uiState.value.episodeOverlayVisible
        _uiState.update { it.copy(episodeOverlayVisible = nowVisible) }
        if (nowVisible) {
            // Cancel the auto-hide timer so the controls don't fade
            // out from under the overlay while it's open.
            hideJob?.cancel()
        } else {
            // Resuming normal playback: re-arm the hide timer.
            restartHideTimer()
        }
    }

    /** Dismiss the episode-list overlay (e.g. on Back press). */
    fun hideEpisodeOverlay() {
        if (!_uiState.value.episodeOverlayVisible) return
        _uiState.update { it.copy(episodeOverlayVisible = false) }
        restartHideTimer()
    }

    /**
     * Jump the player to a different episode (same series).  Resolves
     * the stream URL via the episode's stored URL, falling back to the
     * content_items table lookup when needed — same pattern as
     * [ShowDetailViewModel.resolveStreamUrl].
     */
    fun playEpisode(episode: EpisodeEntity) {
        viewModelScope.launch {
            val url = episode.streamUrl.ifBlank {
                contentDao.byContentId(episode.contentId)?.streamUrl ?: ""
            }
            if (url.isBlank()) return@launch

            // Close the overlay before swapping the stream so the user
            // sees the controls + new episode badge update cleanly.
            _uiState.update { it.copy(episodeOverlayVisible = false) }

            val series = seriesTitle ?: episode.seriesTitle
            initialize(
                streamUrl = url,
                title = "$series S${episode.seasonNumber}E${episode.episodeNumber}",
                isLive = false,
                resumePositionMs = episode.resumePositionMs,
                contentType = "show",
                artworkUrl = artworkUrl,
                seriesTitle = series,
                seasonNumber = episode.seasonNumber,
                episodeNumber = episode.episodeNumber,
            )
        }
    }

    // ── Next episode autoplay ──────────────────────────────────────

    /**
     * When a show episode ends and we know the series context, look up
     * the next episode and start a 10-second countdown.  At zero the
     * player auto-transitions to the next stream.
     */
    private fun maybeStartAutoplay() {
        val series = seriesTitle ?: return
        val season = seasonNumber ?: return
        val episode = episodeNumber ?: return
        if (contentType != "show" || isLive) return

        viewModelScope.launch {
            val next = episodeDao.nextEpisode(series, season, episode)
            if (next == null) {
                Log.d("PlayerVM", "No next episode for $series S${season}E${episode}")
                return@launch
            }

            val nextUrl = next.streamUrl.ifBlank {
                contentDao.byContentId(next.contentId)?.streamUrl ?: ""
            }
            if (nextUrl.isBlank()) return@launch

            _uiState.update {
                it.copy(
                    nextEpisode = NextEpisodeInfo(
                        seriesTitle = series,
                        seasonNumber = next.seasonNumber,
                        episodeNumber = next.episodeNumber,
                        episodeTitle = next.episodeTitle,
                        countdown = 10,
                    ),
                )
            }

            countdownJob = launch {
                for (tick in 9 downTo 0) {
                    delay(1_000)
                    if (!isActive) return@launch
                    _uiState.update { state ->
                        state.copy(
                            nextEpisode = state.nextEpisode?.copy(countdown = tick),
                        )
                    }
                }

                // Countdown reached zero -- play the next episode.
                _uiState.update { it.copy(nextEpisode = null) }
                initialize(
                    streamUrl = nextUrl,
                    title = "$series S${next.seasonNumber}E${next.episodeNumber}",
                    isLive = false,
                    resumePositionMs = 0L,
                    contentType = "show",
                    artworkUrl = artworkUrl,
                    seriesTitle = series,
                    seasonNumber = next.seasonNumber,
                    episodeNumber = next.episodeNumber,
                )
            }
        }
    }

    /**
     * Cancel the autoplay countdown (e.g. when the user presses Back
     * during the "Next Episode" overlay).
     */
    fun cancelAutoplay() {
        countdownJob?.cancel()
        countdownJob = null
        _uiState.update { it.copy(nextEpisode = null) }
    }

    /**
     * Full save + watch history entry.  Called on exit by the composable.
     */
    fun saveOnExit() {
        saveFullContinueWatching()
        insertWatchHistory()
    }

    // ── Fav mode (#11) ──────────────────────────────────────────────

    /**
     * Toggle favourite-channel zapping mode.  Only meaningful during
     * live playback.  When enabled, [zapFavourite] cycles through only
     * channels marked as favourites.
     */
    fun toggleFavMode() {
        if (!isLive) return
        val newEnabled = !_uiState.value.favModeEnabled
        _uiState.update { it.copy(favModeEnabled = newEnabled) }
        if (newEnabled) {
            loadFavourites()
        }
        showControls()
    }

    /**
     * Cycle to the next (+1) or previous (-1) favourite channel.
     * No-op if fav mode is disabled or the list is empty.
     *
     * @param direction +1 for next (D-pad Down), -1 for previous (D-pad Up).
     */
    fun zapFavourite(direction: Int) {
        if (!_uiState.value.favModeEnabled) return
        val favs = favouriteChannels
        if (favs.isEmpty()) return

        // Find the current channel in the favourite list.
        val currentIdx = favs.indexOfFirst { it.streamUrl == streamUrl }
        val nextIdx = if (currentIdx < 0) {
            // Current channel is not in favourites — jump to first.
            0
        } else {
            // Wrap around.
            (currentIdx + direction).mod(favs.size)
        }

        val target = favs[nextIdx]
        switchToChannel(target)
    }

    private fun switchToChannel(target: FavChannel) {
        // Save current position before switching.
        saveFullContinueWatching()

        // Update stream parameters.
        this.streamUrl = target.streamUrl
        this.title = target.displayName
        this.artworkUrl = target.logoUrl

        // Reset per-stream state.
        resumeApplied = false
        _uiState.update {
            it.copy(
                isBuffering = true,
                errorMessage = null,
                channelDisplayName = target.displayName,
            )
        }

        // Switch the ExoPlayer media item.
        player.setMediaItem(MediaItem.fromUri(target.streamUrl))
        player.playWhenReady = true
        player.prepare()

        // Notify the nav layer so PlayerArgs stays in sync.
        _channelSwitchEvent.tryEmit(ChannelSwitchEvent(target.streamUrl, target.displayName, target.logoUrl))

        showControls()
    }

    private fun loadFavourites() {
        viewModelScope.launch {
            val favChannels = channelDao.watchFavourites().first()
            if (favChannels.isEmpty()) {
                favouriteChannels = emptyList()
                return@launch
            }

            // Resolve display names and stream URLs from content_items.
            val liveContent = contentDao.byType("live")
            val contentById = liveContent.associateBy { it.contentId }

            favouriteChannels = favChannels.mapNotNull { ch ->
                val content = contentById[ch.contentId] ?: return@mapNotNull null
                FavChannel(
                    contentId = ch.contentId,
                    displayName = ChannelDeduplicator.cleanChannelName(content.displayName),
                    streamUrl = content.streamUrl,
                    logoUrl = ch.logoUrl,
                    channelNumber = ch.channelNumber,
                )
            }.sortedWith(
                compareBy<FavChannel> { it.channelNumber ?: Int.MAX_VALUE }
                    .thenBy { it.displayName.lowercase() },
            )
        }
    }

    // ── Controls visibility ──────────────────────────────────────────

    private var hideJob: Job? = null

    fun showControls() {
        _uiState.update { it.copy(controlsVisible = true) }
        restartHideTimer()
    }

    fun hideControlsNow() {
        hideJob?.cancel()
        _uiState.update { it.copy(controlsVisible = false) }
    }

    private fun restartHideTimer() {
        hideJob?.cancel()
        hideJob = viewModelScope.launch {
            delay(4_000)
            _uiState.update { it.copy(controlsVisible = false) }
        }
    }

    // ── Persistence helpers ──────────────────────────────────────────

    /**
     * Periodic (every 30s) silent save on a background coroutine.
     */
    private fun startPeriodicSave() {
        saveJob = viewModelScope.launch {
            while (isActive) {
                delay(30_000)
                saveSilentContinueWatching()
            }
        }
    }

    /**
     * Silent save — raw SQL, Room does not emit Flow invalidation events
     * through the normal observer machinery for this path (though Room's
     * internal tracker will still mark the table dirty).  The key point
     * is the *caller* throttles to 30s so any downstream cost is minimal.
     */
    private fun saveSilentContinueWatching() {
        val posMs = player.currentPosition
        if (posMs < 5_000) return
        viewModelScope.launch {
            cwDao.upsertSilent(
                contentId = title,
                contentType = if (isLive) "live" else contentType,
                streamUrl = streamUrl,
                artworkUrl = artworkUrl,
                positionMs = posMs,
                totalMs = player.duration.coerceAtLeast(0),
                lastUpdated = Instant.now(),
                seriesTitle = seriesTitle,
                seasonNumber = seasonNumber,
                episodeNumber = episodeNumber,
            )
        }
    }

    /**
     * Full notification-emitting save — so the home rail refreshes.
     */
    private fun saveFullContinueWatching() {
        val posMs = player.currentPosition
        if (posMs < 5_000) return
        val totalMs = player.duration.coerceAtLeast(0)
        viewModelScope.launch {
            cwDao.upsert(
                ContinueWatchingEntity(
                    contentId = title,
                    contentType = if (isLive) "live" else contentType,
                    streamUrl = streamUrl,
                    artworkUrl = artworkUrl,
                    resumePositionMs = posMs,
                    totalDurationMs = totalMs,
                    lastUpdated = Instant.now(),
                    seriesTitle = seriesTitle,
                    seasonNumber = seasonNumber,
                    episodeNumber = episodeNumber,
                ),
            )
            // Also persist progress on the episode row so the episode list
            // badges ("Resume" / "Watched") stay accurate.
            if (contentType == "show" && seriesTitle != null) {
                val watched = totalMs > 0 && (totalMs - posMs) <= CREDITS_THRESHOLD_MS
                val epContentId = episodeDao.findContentId(
                    seriesTitle!!, seasonNumber ?: 0, episodeNumber ?: 0,
                )
                if (epContentId != null) {
                    episodeDao.updateProgress(epContentId, posMs, watched)
                }
            }
        }
    }

    companion object {
        /** If <= 3 minutes remain, treat the episode as finished. */
        const val CREDITS_THRESHOLD_MS = 180_000L
    }

    private fun insertWatchHistory() {
        val posMs = player.currentPosition
        if (posMs < 5_000) return
        viewModelScope.launch {
            historyDao.insert(
                WatchHistoryEntity(
                    contentId = title,
                    contentType = if (isLive) "live" else contentType,
                    durationWatchedMs = posMs,
                ),
            )
        }
    }

    // ── Cleanup ──────────────────────────────────────────────────────

    override fun onCleared() {
        saveJob?.cancel()
        hideJob?.cancel()
        overlayHideJob?.cancel()
        countdownJob?.cancel()
        player.removeListener(playerListener)
        player.release()
        super.onCleared()
    }
}

/**
 * Snapshot of the player's visible state.  The composable collects this
 * as a [StateFlow] and only recomposes when something meaningful changes.
 */
data class PlayerUiState(
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = true,
    val controlsVisible: Boolean = true,
    val errorMessage: String? = null,
    val channelOverlayVisible: Boolean = false,
    val subtitlesEnabled: Boolean = false,
    /**
     * Current position in [PlayerViewModel.channelList] — drives the
     * "Channel X of Y" counter in [ChannelOverlay].  Exposed through
     * UI state (not a composable param) so the overlay updates after
     * D-pad Up/Down switches channels.
     */
    val currentChannelIndex: Int = 0,
    /** Non-null while the next-episode countdown is active. */
    val nextEpisode: NextEpisodeInfo? = null,
    /** True when favourite-channel zapping is active (#11). */
    val favModeEnabled: Boolean = false,
    /** Display name of the current channel (updated on fav zap). */
    val channelDisplayName: String? = null,
    /**
     * True when the episode-list overlay is open over the player.
     * Opened with D-pad Down while controls are visible during show
     * playback; dismissed with Back or by selecting an episode.
     */
    val episodeOverlayVisible: Boolean = false,
    /**
     * Currently-playing season number — drives the highlight in the
     * episode-list overlay.  Mirrors the ViewModel's [seasonNumber]
     * field but is exposed so the overlay updates after [playEpisode]
     * swaps to a new episode.
     */
    val currentSeasonNumber: Int? = null,
    /** Currently-playing episode number; see [currentSeasonNumber]. */
    val currentEpisodeNumber: Int? = null,
)

/** A single subtitle/CC track available in the current stream. */
data class SubtitleTrack(
    val groupIndex: Int,
    val trackIndex: Int,
    val label: String,
    val isSelected: Boolean,
)

/**
 * Info shown in the "Next Episode" autoplay overlay.
 */
data class NextEpisodeInfo(
    val seriesTitle: String,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val episodeTitle: String,
    val countdown: Int,
)

/**
 * Lightweight model for a favourite channel used by fav-mode zapping.
 */
data class FavChannel(
    val contentId: String,
    val displayName: String,
    val streamUrl: String,
    val logoUrl: String,
    val channelNumber: Int?,
)
