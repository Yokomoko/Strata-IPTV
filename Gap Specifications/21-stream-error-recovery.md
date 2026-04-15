# 21 - Stream Error Recovery

## Current State

`PlayerViewModel`'s `Player.Listener.onPlayerError` handler sets `errorMessage` in the UI state, which renders a static error overlay in `PlayerScreen.kt` showing "Stream unavailable" with the exception's localised message. There is no retry logic, no automatic reconnection, and no fallback behaviour. Once an error occurs, the user must press Back and re-enter the player to attempt the stream again.

The error overlay has no actionable buttons -- it is purely informational.

## Gap

IPTV streams are inherently unreliable: servers go down temporarily, network connections fluctuate (especially on WiFi), and CDN edges rotate. A single transient error should not require the user to manually leave and re-enter the player. Premium players implement transparent retry with exponential backoff, and optionally fall back to a lower quality rendition if the current one is unsustainable. The current "dead end on first error" behaviour is the single biggest UX pain point for live TV viewing.

## User Story

> As a live TV viewer, I want the player to automatically retry when a stream fails so that temporary glitches do not interrupt my viewing experience.

## Acceptance Criteria

1. On a playback error, the player automatically retries after a short delay: 2s, 4s, 8s, 16s (exponential backoff, capped at 30s).
2. During retry, the buffering spinner displays with a "Reconnecting..." label and a retry counter (e.g. "Attempt 2 of 5").
3. After 5 failed retry attempts, the error overlay appears with: the error message, a "Retry" button (D-pad focusable), and a "Back" button.
4. Pressing "Retry" resets the backoff counter and starts a fresh retry cycle.
5. If the stream is HLS with multiple renditions and the error suggests bandwidth issues (e.g. `BehindLiveWindowException`, HTTP 503), the player automatically steps down to the next lower quality before retrying.
6. Successful reconnection clears the error state, resumes playback, and resets the retry counter. The resume position is maintained (for VOD) or jumps to live edge (for live).
7. Network state monitoring: if the device loses WiFi entirely (connectivity change), the player pauses and shows "Waiting for network..." instead of burning through retry attempts. When connectivity returns, it retries immediately.
8. Error classification: distinguish between recoverable errors (network timeout, HTTP 5xx, behind live window) and non-recoverable errors (HTTP 403/404, DRM failure, unsupported codec). Non-recoverable errors skip retry and show the error overlay immediately.

## Technical Approach

1. **Error classification**: Create an `ErrorClassifier` utility that inspects `PlaybackException.errorCode` and the underlying `cause`:
   - Recoverable: `ERROR_CODE_IO_NETWORK_CONNECTION_FAILED`, `ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT`, `ERROR_CODE_BEHIND_LIVE_WINDOW`, HTTP 5xx, `UnknownHostException` (temporary DNS failure).
   - Non-recoverable: `ERROR_CODE_IO_BAD_HTTP_STATUS` with 403/404, `ERROR_CODE_DECODER_INIT_FAILED`, `ERROR_CODE_DRM_*`.
2. **Retry coroutine**: On a recoverable error, launch a `retryJob` in `viewModelScope`:
   ```kotlin
   var attempt = 0
   while (attempt < MAX_RETRIES) {
       delay(backoffMs(attempt)) // 2000, 4000, 8000, 16000, 30000
       _uiState.update { it.copy(retryAttempt = attempt + 1) }
       player.prepare() // Re-prepare the existing MediaItem
       // Wait for STATE_READY or another error
       val result = awaitPlayerReady()
       if (result == Success) { resetRetry(); return }
       attempt++
   }
   // Max retries exceeded -- show error overlay with manual retry button
   ```
3. **awaitPlayerReady()**: Use a `suspendCancellableCoroutine` that registers a temporary `Player.Listener` waiting for `STATE_READY` (success) or another `onPlayerError` (failure).
4. **Quality fallback**: Before retry attempt 3+, if the stream is ABR, reduce `TrackSelectionParameters.maxVideoSize` by one resolution tier. Restore after successful reconnection (with a delay to let the network stabilise).
5. **Network monitoring**: Register a `ConnectivityManager.NetworkCallback` in the ViewModel. When connectivity is lost, cancel the retry coroutine and enter "Waiting for network" state. When connectivity returns, trigger an immediate retry.
6. **PlayerUiState extension**: Add `retryAttempt: Int? = null` (null = not retrying), `isWaitingForNetwork: Boolean = false`.
7. **UI updates**:
   - During retry: show buffering spinner with "Reconnecting... Attempt N of 5" text below.
   - During network loss: show a WiFi-off icon with "Waiting for network..." text.
   - After max retries: show the existing error card but with added "Retry" and "Back" `Surface` buttons.
8. **Live edge reset**: For live streams after successful reconnection, call `player.seekToDefaultPosition()` to jump to the live edge rather than attempting to play from the stale position.

## Priority

**P1 -- High** (critical reliability feature; IPTV streams are inherently flaky and the current behaviour is a UX failure mode)

## Effort Estimate

**3-4 days**
- 0.5 day: `ErrorClassifier` utility + error categorisation
- 1 day: retry coroutine with exponential backoff + `awaitPlayerReady` suspension
- 0.5 day: network connectivity monitoring + "waiting for network" state
- 0.5 day: quality fallback logic for ABR streams
- 0.5 day: UI updates (reconnecting label, retry/back buttons on error overlay)
- 0.5 day: testing across error scenarios (network off/on, server timeout, 404, behind live window)
