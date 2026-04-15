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
import androidx.media3.exoplayer.ExoPlayer
import com.strata.tv.data.db.ContentDao
import com.strata.tv.data.db.ContinueWatchingDao
import com.strata.tv.data.db.ContinueWatchingEntity
import com.strata.tv.data.db.EpisodeDao
import com.strata.tv.data.db.EpisodeEntity
import com.strata.tv.data.db.WatchHistoryDao
import com.strata.tv.data.db.WatchHistoryEntity
import com.strata.tv.ui.nav.ChannelPlayInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
 */
@HiltViewModel
class PlayerViewModel @Inject constructor(
    application: Application,
    private val cwDao: ContinueWatchingDao,
    private val historyDao: WatchHistoryDao,
    private val episodeDao: EpisodeDao,
    private val contentDao: ContentDao,
) : AndroidViewModel(application) {

    // ── ExoPlayer ────────────────────────────────────────────────────

    val player: ExoPlayer = ExoPlayer.Builder(application).build()

    // ── UI state ─────────────────────────────────────────────────────

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

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

    // ── Player listener ──────────────────────────────────────────────

    private val playerListener = object : Player.Listener {

        override fun onPlaybackStateChanged(playbackState: Int) {
            val buffering = playbackState == Player.STATE_BUFFERING
            val ended = playbackState == Player.STATE_ENDED

            _uiState.update { it.copy(isBuffering = buffering) }

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
        }

        override fun onPlayerError(error: PlaybackException) {
            if (retryCount < maxRetries) {
                retryCount++
                val delayMs = (1000L * (1 shl (retryCount - 1).coerceAtMost(4)))
                    .coerceAtMost(30_000L)
                _uiState.update {
                    it.copy(errorMessage = "Retrying... (attempt $retryCount/$maxRetries)")
                }
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
            )
        }
    }

    /**
     * Full notification-emitting save — so the home rail refreshes.
     */
    private fun saveFullContinueWatching() {
        val posMs = player.currentPosition
        if (posMs < 5_000) return
        viewModelScope.launch {
            cwDao.upsert(
                ContinueWatchingEntity(
                    contentId = title,
                    contentType = if (isLive) "live" else contentType,
                    streamUrl = streamUrl,
                    artworkUrl = artworkUrl,
                    resumePositionMs = posMs,
                    totalDurationMs = player.duration.coerceAtLeast(0),
                    lastUpdated = Instant.now(),
                ),
            )
        }
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
