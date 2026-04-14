package com.strata.tv.ui.player

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.strata.tv.data.db.ChannelDao
import com.strata.tv.data.db.ContentDao
import com.strata.tv.data.db.ContinueWatchingDao
import com.strata.tv.data.db.ContinueWatchingEntity
import com.strata.tv.data.db.WatchHistoryDao
import com.strata.tv.data.db.WatchHistoryEntity
import com.strata.tv.domain.ChannelDeduplicator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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

    // ── Fav mode state (#11) ─────────────────────────────────────────

    /**
     * Resolved favourite channel list, loaded once from [ChannelDao.watchFavourites].
     * Each pair is (displayName, streamUrl) matching the channel content.
     */
    private var favouriteChannels: List<FavChannel> = emptyList()

    /** Callback to inform the Shell/nav layer that a channel switch happened. */
    var onChannelSwitch: ((streamUrl: String, title: String, artworkUrl: String) -> Unit)? = null

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
            _uiState.update {
                it.copy(errorMessage = error.localizedMessage ?: "Playback error")
            }
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

        // Reset per-stream state so the resume-seek fires once for the
        // new URL and buffering spinner shows until STATE_READY.
        resumeApplied = false
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
        onChannelSwitch?.invoke(target.streamUrl, target.displayName, target.logoUrl)

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
    /** True when favourite-channel zapping is active (#11). */
    val favModeEnabled: Boolean = false,
    /** Display name of the current channel (updated on fav zap). */
    val channelDisplayName: String? = null,
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
