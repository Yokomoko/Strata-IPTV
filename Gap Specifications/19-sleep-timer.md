# 19 - Sleep Timer

## Current State

The player has `view.keepScreenOn = true` set via a `DisposableEffect` in `PlayerScreen.kt`, ensuring the screen never dims during playback. There is no timer mechanism to automatically stop playback after a user-defined period. The ViewModel's periodic save runs every 30 seconds but has no concept of a countdown or auto-stop.

## Gap

Users who fall asleep watching TV waste bandwidth and (on Fire Stick) increase device wear from continuous decoding heat. A sleep timer is a standard feature on smart TVs, cable boxes, and streaming devices. It is especially relevant for the IPTV use case where live TV may stream indefinitely.

## User Story

> As a viewer watching in bed, I want to set a sleep timer so that playback stops automatically after a set time, saving bandwidth and letting me fall asleep without worry.

## Acceptance Criteria

1. A "Sleep Timer" option is accessible from the player controls overlay (via a moon/clock icon in the top-right action row).
2. Pressing the button opens a D-pad-navigable panel with options: Off, 15 min, 30 min, 45 min, 1 hour, 2 hours, "End of episode" (VOD only).
3. When a timer is set, a small countdown badge appears in the top-right corner of the player (always visible, not tied to controls overlay visibility), showing remaining time in "Xh Xm" format.
4. When the timer expires: playback pauses (not stops -- so position is preserved), a brief "Sleep timer ended" toast appears, and the screen dims after the standard Android timeout.
5. The user can cancel or change the timer at any time by re-opening the panel.
6. "End of episode" mode pauses playback when `Player.STATE_ENDED` is reached (blocking the auto-play next episode feature from spec 15).
7. The timer persists across stream switches within the same player session (e.g. if auto-play moves to the next episode, the timer continues counting down).

## Technical Approach

1. **Timer coroutine**: In `PlayerViewModel`, add a `sleepTimerJob: Job?` that launches a `delay(durationMs)` coroutine in `viewModelScope`. On completion, call `player.pause()` and update `PlayerUiState`.
2. **State**: Add to `PlayerUiState`: `sleepTimerRemainingMs: Long? = null` (null means off), `sleepTimerEndOfEpisode: Boolean = false`.
3. **Countdown updates**: The timer coroutine updates `sleepTimerRemainingMs` every 60 seconds (no need for per-second precision in the badge). Use `withContext(Dispatchers.Main)` for state updates.
4. **Timer management**: `setSleepTimer(durationMs: Long)` cancels any existing `sleepTimerJob` and starts a new one. `cancelSleepTimer()` cancels and clears the state. `setSleepTimerEndOfEpisode()` sets a flag checked in the `STATE_ENDED` listener.
5. **UI**: 
   - `SleepTimerPanel` composable: vertical list of duration options, styled like the subtitle/audio panels.
   - `SleepTimerBadge` composable: small pill with clock icon and remaining time, rendered in the `PlayerScreen` Box at `Alignment.TopEnd` with `padding(top = 24.dp, end = 24.dp)` -- offset to avoid overlap with the title bar. Only visible when timer is active. Semi-transparent background (`StrataColors.SurfaceFloat.copy(alpha = 0.7f)`).
6. **Interaction with auto-play**: If `sleepTimerEndOfEpisode` is true and a next-episode countdown starts (spec 15), cancel the auto-play countdown and pause instead.
7. **keepScreenOn**: When the sleep timer fires, set `view.keepScreenOn = false` so the screen can dim normally.

## Priority

**P3 -- Low** (quality-of-life feature, low complexity, high user satisfaction for nightly TV watchers)

## Effort Estimate

**1-2 days**
- 0.5 day: timer coroutine logic in ViewModel
- 0.5 day: SleepTimerPanel composable + D-pad navigation
- 0.25 day: SleepTimerBadge composable
- 0.25-0.5 day: interaction with auto-play + keepScreenOn toggling + testing
