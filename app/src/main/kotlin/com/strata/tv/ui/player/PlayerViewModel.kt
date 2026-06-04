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
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
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
            // Bumped from 8/15s to 20/45s: the original tight budget was a
            // workaround for the OOM that's since been fixed (streaming
            // persist), and it caused frequent mid-stream rebuffering on
            // variable WiFi.  45 s max ≈ a few MB of compressed video —
            // comfortably within heap now — and a 20 s floor lets the
            // player ride out short network dips without stalling.
            /* minBufferMs = */ 20_000,
            /* maxBufferMs = */ 45_000,
            // 2.5 s before first frame, 5 s after a rebuffer — fewer but
            // longer buffering pauses beat constant micro-stalls.
            /* bufferForPlaybackMs = */ 2_500,
            /* bufferForPlaybackAfterRebufferMs = */ 5_000,
        )
        .setPrioritizeTimeOverSizeThresholds(true)
        .build()

    /**
     * Renderers for Fire Stick.  We deliberately DON'T enable software
     * decoder fallback: for a stream the HW decoder can't handle (e.g.
     * 4K HEVC L5.0), SW decoding 4K is too slow and just buffers forever.
     * Instead we let the capability error fire fast and switch to a
     * LOWER-QUALITY VARIANT of the same episode (see variantUrls /
     * tryNextVariant) — the provider ships 1080p/720p alongside the 4K,
     * and those decode fine in hardware.
     */
    private val renderersFactory: androidx.media3.exoplayer.DefaultRenderersFactory =
        androidx.media3.exoplayer.DefaultRenderersFactory(application)
            .setEnableDecoderFallback(false)

    /**
     * HTTP datasource configured for IPTV streams.  Two important
     * non-default flags:
     *
     *  1. **setAllowCrossProtocolRedirects(true)** — IPTV providers
     *     routinely 302-redirect from the auth host (HTTPS) onto a CDN
     *     edge (HTTP) or vice versa.  Without this, ExoPlayer throws
     *     `Response code: 302` and playback fails.
     *  2. **User-Agent set to a media-player string** — some Xtream
     *     deployments rate-limit or 403 the default OkHttp UA.
     *     Lavf/VLC-style UAs are widely accepted.
     *
     *  Connect / read timeouts bumped to 15 s — Fire Stick is often on
     *  WiFi with variable latency and the default 8 s drops too many
     *  legit channel switches.
     */
    private val httpDataSourceFactory: DefaultHttpDataSource.Factory =
        DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent("VLC/3.0.20 LibVLC/3.0.20")
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(15_000)

    private val mediaSourceFactory: DefaultMediaSourceFactory =
        DefaultMediaSourceFactory(application)
            .setDataSourceFactory(DefaultDataSource.Factory(application, httpDataSourceFactory))

    val player: ExoPlayer = ExoPlayer.Builder(application)
        .setLoadControl(loadControl)
        .setMediaSourceFactory(mediaSourceFactory)
        .setRenderersFactory(renderersFactory)
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
    /**
     * Stable content-hash ID of the currently-playing item.  Used as
     * the primary key for ContinueWatching / WatchHistory writes so
     * two items with the same display title (e.g. two films called
     * "Frankenstein") don't overwrite each other's resume position.
     *
     * Empty string means the caller didn't supply one — we fall back
     * to writing `title` for legacy compatibility, but every new
     * call site should pass the real `content_id` hash through
     * [PlayerArgs] → [initialize].
     */
    private var contentId: String = ""
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
    private var bufferingWatchdogJob: Job? = null

    // ── Quality-variant fallback ─────────────────────────────────────
    // Ordered best→worst stream URLs for the current item.  When the
    // best one can't be decoded (4K HEVC on a 1080p Fire Stick) or stalls,
    // we step down to the next.  Populated for episodes from the stored
    // alt_stream_urls; single-element for everything else.
    private var variantUrls: List<String> = emptyList()
    private var variantIndex = 0

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

            // Buffering watchdog: if we sit in BUFFERING for too long with
            // no progress, give up and surface an error instead of spinning
            // forever.  This is the visible symptom when a stream is in a
            // format the device can't actually play — e.g. 4K HEVC (L5.0)
            // that the HW decoder rejects and the SW decoder is too slow to
            // keep up with, so the buffer never fills.
            if (buffering) {
                bufferingWatchdogJob?.cancel()
                bufferingWatchdogJob = viewModelScope.launch {
                    delay(BUFFER_TIMEOUT_MS)
                    if (_uiState.value.isBuffering) {
                        // A long stall can be a too-heavy codec or just a
                        // slow source — stepping down to a lower-quality
                        // variant fixes both when one is available.
                        ensureVariantsLoaded()
                        if (tryNextVariant()) return@launch
                        retryJob?.cancel()
                        _uiState.update {
                            it.copy(
                                isBuffering = false,
                                errorMessage = "This stream is taking too long to load. " +
                                    "It may be in a format this device can't play " +
                                    "(e.g. 4K / HEVC). Try again or go back.",
                            )
                        }
                    }
                }
            } else {
                bufferingWatchdogJob?.cancel()
            }

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
            // Codec-capability failures can never succeed on a retry — the
            // device simply can't decode this profile/level.  Fail fast with
            // a clear message instead of burning 5 pointless retries.
            if (error.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES ||
                error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
                error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED
            ) {
                // The device can't decode this variant — switch to a
                // lower-quality source rather than retrying (a retry of
                // the SAME format can never succeed).  Load the variant
                // list on-demand first: the capability error often beats
                // the eager async load in initialize().
                bufferingWatchdogJob?.cancel()
                retryJob?.cancel()
                viewModelScope.launch {
                    ensureVariantsLoaded()
                    if (tryNextVariant()) return@launch
                    _uiState.update {
                        it.copy(
                            isBuffering = false,
                            errorMessage = "This episode is in a video format this device " +
                                "can't play (e.g. 4K / HEVC) and no lower-quality version " +
                                "is available from the provider.",
                        )
                    }
                }
                return
            }
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
                // Exhausted retries on this variant — try a lower-quality
                // one before giving up entirely.
                viewModelScope.launch {
                    ensureVariantsLoaded()
                    if (tryNextVariant()) return@launch
                    _uiState.update {
                        it.copy(errorMessage = error.localizedMessage ?: "Stream unavailable after $maxRetries retries")
                    }
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
        contentId: String = "",
        seriesTitle: String? = null,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
    ) {
        // No-op if the caller is re-asserting the same stream we're
        // already set up for (e.g. a recomposition that doesn't change
        // the nav args).
        if (initialized && this.streamUrl == streamUrl) return

        val firstTime = !initialized
        val urlChanged = initialized && this.streamUrl != streamUrl

        // Force-release the previous stream before swapping in the new
        // one.  Without this, channel-switch (D-pad Up/Down on the
        // guide) and next-episode autoplay leave the old HTTP/MPEG-TS
        // socket lingering long enough that single-connection IPTV
        // plans see two connections at once and reject the new stream
        // with "All slots are currently in use".
        //
        // The teardown must happen BEFORE the new media item is set
        // because ExoPlayer would otherwise let the old source linger
        // in the background until GC kicks in.  stop() + clearMediaItems
        // synchronously closes the underlying datasource.
        if (urlChanged) {
            runCatching {
                player.stop()
                player.clearMediaItems()
            }
        }

        this.streamUrl = streamUrl
        this.title = title
        this.contentId = contentId
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

        // Seed the variant list with the primary URL; load the
        // lower-quality fallbacks (stored on the content row for live /
        // movie / show alike) asynchronously so a decode failure can
        // step down instead of dead-ending.
        variantUrls = listOf(streamUrl)
        variantIndex = 0
        if (contentId.isNotBlank()) {
            viewModelScope.launch {
                val alts = runCatching { contentDao.byContentId(contentId) }
                    .getOrNull()
                    ?.altStreamUrls
                    ?.split("\n")
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
                    .orEmpty()
                if (alts.isNotEmpty()) {
                    variantUrls = listOf(streamUrl) + alts
                }
            }
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

    /**
     * Load the fallback variant URLs from the content row if we haven't
     * already.  Called on-demand from the error/stall handlers because a
     * 4K capability error can fire BEFORE the eager async load in
     * [initialize] finishes — without this, the first failure sees only
     * the primary URL and dead-ends instead of stepping down.
     */
    private suspend fun ensureVariantsLoaded() {
        if (variantUrls.size > 1) return
        if (contentId.isBlank()) return
        val primary = variantUrls.firstOrNull() ?: streamUrl
        val alts = runCatching { contentDao.byContentId(contentId) }
            .getOrNull()
            ?.altStreamUrls
            ?.split("\n")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
        if (alts.isNotEmpty()) variantUrls = listOf(primary) + alts
    }

    /**
     * Step down to the next lower-quality variant of the current item,
     * if one exists.  Returns true if it switched (caller should treat
     * the failure as handled), false when we've run out of variants.
     */
    private fun tryNextVariant(): Boolean {
        if (variantIndex + 1 >= variantUrls.size) return false
        variantIndex++
        val next = variantUrls[variantIndex]
        android.util.Log.i(
            "PlayerVM",
            "Stepping down to variant ${variantIndex + 1}/${variantUrls.size}: $next",
        )
        retryCount = 0
        retryJob?.cancel()
        bufferingWatchdogJob?.cancel()
        // Clear the error so the overlay doesn't flash; just show the
        // buffering spinner while the lower-quality source loads.
        _uiState.update { it.copy(errorMessage = null, isBuffering = true) }
        streamUrl = next
        player.setMediaItem(MediaItem.fromUri(next))
        player.playWhenReady = true
        player.prepare()
        return true
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
            contentId = channel.contentId,
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
                contentId = episode.contentId,
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
                    contentId = next.contentId,
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
     * Full save + watch history entry + stream teardown.  Called by the
     * composable when the user exits the player.
     *
     * Stream teardown is critical for IPTV providers that limit
     * simultaneous connections — without it, the OkHttp/MPEG-TS
     * connection from the previous stream stays open and the provider
     * keeps counting it as an active slot.  The user then sees
     * "Your plan allows 1 simultaneous connection. All slots in use"
     * on the next attempt.
     *
     * `clearMediaItems()` drops the URL and tears down the underlying
     * HTTP connection in the next event-loop tick.  We also call
     * `stop()` to make sure the loader thread isn't mid-fetch when
     * the connection is closed.  Clearing `initialized` means the
     * next [initialize] call will treat the player as fresh.
     */
    fun saveOnExit() {
        saveFullContinueWatching()
        insertWatchHistory()
        runCatching {
            player.stop()
            player.clearMediaItems()
        }
        initialized = false
        resumeApplied = false
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

        // Switch the ExoPlayer media item.  Tear down the previous
        // stream first — same reason as the urlChanged branch in
        // [initialize]: single-connection IPTV plans see overlap if
        // the old socket isn't explicitly closed before the new one
        // opens.
        runCatching {
            player.stop()
            player.clearMediaItems()
        }
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
        // Live TV doesn't belong in Continue Watching — there's no
        // meaningful resume position for a linear channel.
        if (isLive) return
        val posMs = player.currentPosition
        if (posMs < 5_000) return
        viewModelScope.launch {
            cwDao.upsertSilent(
                // Real content_id hash where available; fall back to
                // title only when an old caller path hasn't been
                // upgraded yet.  See [contentId] kdoc for why title
                // is a poor primary key.
                contentId = contentId.ifBlank { title },
                contentType = if (isLive) "live" else contentType,
                streamUrl = streamUrl,
                artworkUrl = artworkUrl,
                positionMs = posMs,
                totalMs = player.duration.coerceAtLeast(0),
                lastUpdated = Instant.now(),
                seriesTitle = seriesTitle,
                seasonNumber = seasonNumber,
                episodeNumber = episodeNumber,
                title = title,
            )
        }
    }

    /**
     * Full notification-emitting save — so the home rail refreshes.
     */
    private fun saveFullContinueWatching() {
        if (isLive) return
        val posMs = player.currentPosition
        if (posMs < 5_000) return
        val totalMs = player.duration.coerceAtLeast(0)
        viewModelScope.launch {
            cwDao.upsert(
                ContinueWatchingEntity(
                    contentId = contentId.ifBlank { title },
                    contentType = if (isLive) "live" else contentType,
                    streamUrl = streamUrl,
                    artworkUrl = artworkUrl,
                    resumePositionMs = posMs,
                    totalDurationMs = totalMs,
                    lastUpdated = Instant.now(),
                    seriesTitle = seriesTitle,
                    seasonNumber = seasonNumber,
                    episodeNumber = episodeNumber,
                    title = title,
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

        /**
         * Max continuous time the player may sit in BUFFERING before we
         * give up and surface an error.  Long enough to ride out a slow
         * first load / network dip on Fire Stick WiFi, short enough that
         * an unplayable (e.g. 4K HEVC) stream doesn't spin forever.
         */
        const val BUFFER_TIMEOUT_MS = 40_000L
    }

    private fun insertWatchHistory() {
        val posMs = player.currentPosition
        if (posMs < 5_000) return
        viewModelScope.launch {
            historyDao.insert(
                WatchHistoryEntity(
                    contentId = contentId.ifBlank { title },
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
        retryJob?.cancel()
        bufferingWatchdogJob?.cancel()
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
