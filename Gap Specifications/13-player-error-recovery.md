# 13 - Player Error Recovery and Stream Retry

## Current State

`PlayerViewModel` has an `onPlayerError` listener that sets `errorMessage` in the UI state. The `PlayerScreen` displays the error in a static overlay:

```
[Error icon]
Stream unavailable
<error message>
```

There is:
- No retry button.
- No automatic retry.
- No way to recover without pressing Back and re-entering the channel.
- No distinction between transient errors (network hiccup, buffering timeout) and permanent errors (invalid URL, DRM failure).

For live streams, transient failures are very common -- network glitches, CDN rotation, server-side restarts. A TV app that stops playing and shows an error dialog on every hiccup is unusable.

## Gap

The player has zero error recovery. Any stream error is a dead end requiring manual intervention (back out, re-select the channel). This is unacceptable for a live TV app where streams are inherently less reliable than local media.

## User Story

As a user, when a live stream has a momentary failure, I want the player to automatically retry so I don't have to manually restart the channel every time there's a network hiccup.

## Acceptance Criteria

- [ ] On a transient error (network timeout, source error), the player automatically retries after a 3-second delay.
- [ ] Up to 3 automatic retries are attempted before showing the error dialog.
- [ ] During retry attempts, a subtle "Reconnecting..." indicator appears (not a full error dialog).
- [ ] The retry counter resets after 30 seconds of successful playback (so a later failure gets a fresh set of retries).
- [ ] After all retries are exhausted, the error dialog appears WITH a "Retry" button that the user can press to try again.
- [ ] For live channels, the error dialog also offers "Try next channel" which triggers a channel-down switch (Gap 01).
- [ ] Permanent errors (HTTP 403, 404, unsupported codec) are detected and shown immediately without retries.
- [ ] The retry mechanism does not apply to VOD seek errors (which have a different error profile).

## Technical Approach

### Retry Logic in PlayerViewModel

```kotlin
private var retryCount = 0
private var lastSuccessTime = 0L
private val MAX_RETRIES = 3
private val RETRY_DELAY_MS = 3_000L
private val RETRY_RESET_MS = 30_000L

override fun onPlayerError(error: PlaybackException) {
    // Reset retry count if we've been playing successfully
    if (System.currentTimeMillis() - lastSuccessTime > RETRY_RESET_MS) {
        retryCount = 0
    }
    
    if (isTransient(error) && retryCount < MAX_RETRIES) {
        retryCount++
        _uiState.update { it.copy(reconnecting = true) }
        viewModelScope.launch {
            delay(RETRY_DELAY_MS)
            player.prepare() // Re-prepare the same media item
        }
    } else {
        _uiState.update { 
            it.copy(
                errorMessage = error.localizedMessage ?: "Playback error",
                reconnecting = false,
            )
        }
    }
}

private fun isTransient(error: PlaybackException): Boolean {
    return when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> true
        else -> false
    }
}
```

### UI Changes

Add `reconnecting: Boolean` to `PlayerUiState`. Show a subtle reconnecting indicator (small text + spinner at the top of the screen) instead of the full error overlay during retries.

Add a "Retry" button to the error overlay (focusable Surface).

### Files to Modify

| File | Change |
|------|--------|
| `ui/player/PlayerViewModel.kt` | Add retry logic, `isTransient()`, retry counter, "Retry" action. |
| `ui/player/PlayerScreen.kt` | Add "Reconnecting..." indicator, add "Retry" and "Try next channel" buttons to error overlay. |

## Priority

**High** -- Stream reliability issues are the #1 complaint for IPTV apps. Without auto-retry, every minor network blip requires the user to manually re-enter the channel.

## Effort Estimate

**Small (1-2h)** -- The retry logic is straightforward. The main work is distinguishing transient from permanent errors using ExoPlayer's error codes and testing with real stream failures.
