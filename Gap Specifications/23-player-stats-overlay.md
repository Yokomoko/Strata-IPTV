# 23 - Player Stats Overlay (Debug)

## Current State

The player has no diagnostic or stats display. `PlayerViewModel` does not collect any telemetry beyond the basic `isPlaying`, `isBuffering`, and `errorMessage` fields in `PlayerUiState`. There is no way for the user to see the current bitrate, codec in use, buffer health, dropped frames, or network throughput. Debugging stream issues requires connecting to `adb logcat` and filtering for ExoPlayer's internal logs.

The app uses `debugPrint` for logging (per project memory: reaches logcat in release builds), but there is no in-app visual equivalent.

## Gap

When users report "the stream is laggy" or "it keeps buffering", the developer (and the technically-inclined user) needs objective metrics to diagnose whether the issue is network throughput, server-side, codec decode performance, or buffer under-run. A stats overlay (like YouTube's "Stats for nerds" or VLC's codec information panel) is essential for support and debugging. On Fire Stick, where ADB access is not always convenient, an in-app overlay is far more practical.

## User Story

> As a technically-inclined user or developer, I want to view real-time player statistics (bitrate, codec, buffer health, dropped frames) so that I can diagnose stream quality issues without needing ADB access.

## Acceptance Criteria

1. A "Stats" toggle is accessible from the player controls overlay (via a chart/info icon) or via a hidden gesture (press Play 5 times quickly).
2. When enabled, a semi-transparent stats panel appears in the top-left corner of the player, updating every 1-2 seconds.
3. The panel displays:
   - **Video codec**: e.g. "H.264 (avc1)" or "HEVC (hev1)"
   - **Video resolution**: e.g. "1920x1080 @ 30fps"
   - **Video bitrate**: current measured bitrate in Mbps
   - **Audio codec**: e.g. "AAC-LC Stereo" or "AC3 5.1"
   - **Audio bitrate**: e.g. "128 kbps"
   - **Buffer health**: seconds of buffered content ahead (e.g. "Buffer: 12.3s")
   - **Dropped frames**: total dropped frames since playback started
   - **Network throughput**: estimated bandwidth in Mbps (from ExoPlayer's bandwidth meter)
   - **Stream URI**: truncated to last 60 characters (useful for identifying CDN issues)
   - **Decoder**: hardware vs. software decode indicator
4. The panel does not interfere with D-pad navigation (it is not focusable).
5. The stats panel can be dismissed by pressing the Stats button again or by toggling via the controls overlay.
6. In release builds, the Stats button is hidden unless enabled in Settings via a "Developer Options" toggle (requires tapping the app version 7 times to unlock, similar to Android's developer options pattern).

## Technical Approach

1. **Stats collection**: In `PlayerViewModel`, add a `statsJob: Job?` that runs a coroutine collecting stats every 1 second:
   ```kotlin
   val videoFormat = player.videoFormat
   val audioFormat = player.audioFormat
   val droppedFrames = player.videoDecoderCounters?.droppedBufferCount ?: 0
   val bufferedMs = player.bufferedPosition - player.currentPosition
   val bandwidth = player.analyticsCollector... // or DefaultBandwidthMeter
   ```
2. **Bandwidth meter**: Inject `DefaultBandwidthMeter.getSingletonInstance(context)` and read `bitrateEstimate` for network throughput.
3. **Decoder info**: Use `player.videoDecoderCounters?.decoderName` to determine hardware vs. software. Format names like "OMX.qcom.video.decoder.avc" indicate hardware; "c2.android.avc.decoder" indicates software.
4. **PlayerUiState extension**: Add `statsOverlayVisible: Boolean = false` and `playerStats: PlayerStats?` data class containing all the above fields.
5. **UI**: A `StatsOverlay` composable at `Alignment.TopStart` with `padding(16.dp)`. Renders a `Column` of `Text` rows with monospace font, small size (11sp), white text on `Color(0x99000000)` background. Not focusable (`focusable(false)`).
6. **Developer options**: In `SettingsScreen`, add a hidden counter on the version text tap. After 7 taps, enable a "Developer Options" section with the Stats toggle. Store `developer_options_enabled` and `stats_overlay_enabled` in DataStore.
7. **Performance**: The 1-second polling coroutine is lightweight (reads fields, no allocation), but skip the update if `statsOverlayVisible == false` to avoid unnecessary state emissions.
8. **Alternative activation**: Detect 5 rapid D-pad Centre presses within 2 seconds (via a timestamp ring buffer in `onPreviewKeyEvent`) as a hidden activation gesture that does not require Settings navigation.

## Priority

**P2 -- Medium** (essential for support and debugging; low user visibility but high developer utility; helps diagnose issues reported by users in the field)

## Effort Estimate

**1-2 days**
- 0.5 day: stats collection coroutine + PlayerStats data class
- 0.5 day: StatsOverlay composable
- 0.25 day: toggle logic (button + hidden gesture)
- 0.25-0.5 day: developer options in Settings + DataStore
- 0.25 day: testing on Fire Stick (verify decoder names, bandwidth readings)
