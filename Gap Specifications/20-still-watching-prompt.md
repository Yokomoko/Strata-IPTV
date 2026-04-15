# 20 - Binge-Watching "Are You Still Watching?" Prompt

## Current State

The player has no idle detection or session-length tracking. Playback continues indefinitely as long as the stream is active. The periodic save coroutine in `PlayerViewModel` runs every 30 seconds but only persists position data -- it does not track cumulative watch time or user interaction recency.

If next-episode auto-play (spec 15) is implemented, the player could theoretically auto-play through an entire series season without any user interaction.

## Gap

Netflix, Amazon Video, and other premium services display an "Are you still watching?" prompt after extended unattended playback (typically 3 consecutive auto-played episodes or 4+ hours of continuous viewing). This prevents wasteful bandwidth consumption and is a user courtesy. For IPTV sources with connection limits, it also prevents a viewer from unknowingly holding a stream slot.

## User Story

> As a binge-watcher, I want the app to check if I am still watching after several consecutive episodes so that it does not keep streaming if I have fallen asleep.

## Acceptance Criteria

1. After 3 consecutive auto-played episodes (no user interaction between them) OR 4 hours of continuous playback without any remote button press, a full-screen "Still watching?" prompt appears over the player.
2. The prompt shows: series poster/thumbnail, current episode title, and two D-pad-focusable buttons: "Continue Watching" (primary) and "Stop" (secondary).
3. Pressing "Continue Watching" dismisses the prompt, resumes playback, and resets the interaction counters.
4. Pressing "Stop" pauses playback, saves position, and returns to the show detail screen (or home if not a series).
5. If no button is pressed within 60 seconds, playback pauses automatically (same as "Stop").
6. Any D-pad interaction during normal playback (play/pause, seek, showing controls) resets the interaction timer.
7. The feature is configurable in Settings: On (default) / Off, with adjustable episode threshold (2 / 3 / 5 episodes).
8. The prompt does not trigger during live TV (only VOD content).

## Technical Approach

1. **Interaction tracking**: In `PlayerViewModel`, add `lastInteractionTime: Instant` updated by `showControls()`, `togglePlayPause()`, `seekRelative()`, and any other user-triggered function. Add `autoPlayCount: Int` incremented by the next-episode auto-play logic (spec 15), reset to 0 on any user interaction.
2. **Threshold check**: Add a periodic coroutine (every 60s) that checks:
   - `autoPlayCount >= threshold` (default 3), OR
   - `Duration.between(lastInteractionTime, Instant.now()) >= 4.hours`
   
   If either condition is met and `isLive == false`, set `stillWatchingPromptVisible: Boolean = true` in `PlayerUiState` and pause the auto-play countdown if one is active.
3. **Prompt overlay**: A `StillWatchingOverlay` composable rendered in the `PlayerScreen` Box with highest z-order. Semi-transparent black scrim (`Color(0xCC000000)`) over the video. Centered card with:
   - Series poster (from `artworkUrl` in ViewModel state)
   - "Still watching [Series Title]?" heading
   - Current episode label
   - Two `Surface` buttons using Strata's standard button styling
4. **Timeout auto-pause**: A 60-second `LaunchedEffect` coroutine starts when the prompt becomes visible. If it completes without dismissal, call `player.pause()` and navigate back via the `onExit` callback.
5. **Settings**: Add `still_watching_enabled: Boolean` and `still_watching_episode_threshold: Int` to DataStore. Default: enabled, threshold = 3.
6. **Reset on dismiss**: `dismissStillWatchingPrompt()` sets `autoPlayCount = 0`, `lastInteractionTime = Instant.now()`, and clears the prompt visibility.

## Priority

**P3 -- Low** (depends on spec 15 auto-play implementation; good UX courtesy but not a launch blocker)

## Effort Estimate

**1-2 days**
- 0.5 day: interaction tracking + threshold check logic in ViewModel
- 0.5 day: StillWatchingOverlay composable + D-pad focus
- 0.25 day: auto-pause timeout logic
- 0.25-0.5 day: Settings integration + testing with auto-play flow
