# 13 - Playback Speed Control

## Current State

`PlayerViewModel` does not expose any speed control API. `ExoPlayer.setPlaybackSpeed()` is never called. The player always runs at 1.0x. The controls overlay in `PlayerScreen.kt` has no speed selection UI. `PlayerUiState` has no speed-related field.

## Gap

VOD viewers (movies, series, and catch-up content) expect the ability to watch at different speeds -- 0.5x for studying foreign language content, 1.25x-1.5x for binge sessions, 2x for review. This is a standard feature on YouTube, Netflix (mobile), and most premium VOD players. It does not apply to live streams where real-time pacing is inherent.

## User Story

> As a VOD viewer, I want to change playback speed (0.5x to 2x) so that I can watch content at my preferred pace.

## Acceptance Criteria

1. A "Speed" chip/button appears in the controls overlay bottom row for VOD content only (hidden when `isLive == true`).
2. The chip displays the current speed (e.g. "1x", "1.5x").
3. Pressing the chip cycles through: 0.5x, 0.75x, 1x, 1.25x, 1.5x, 2x, then back to 0.5x. Long-pressing opens a selector panel for direct jump to any speed.
4. Audio pitch correction is enabled (ExoPlayer default) so voices do not sound distorted at non-1x speeds.
5. The buffering spinner adapts: if the player is buffering at an elevated speed, the speed indicator remains visible.
6. When the stream ends or the user exits, speed resets to 1x for the next session (speed is session-scoped, not persisted).
7. The progress bar time-remaining label accounts for the current speed (optional -- nice to have).

## Technical Approach

1. **Speed application**: Call `player.setPlaybackParameters(PlaybackParameters(speed))` on the `ExoPlayer` instance. ExoPlayer's `SilenceSkippingAudioProcessor` and `SonicAudioProcessor` handle pitch correction automatically when `PlaybackParameters.pitch` is left at the default 1.0.
2. **Speed state**: Add `playbackSpeed: Float = 1.0f` to `PlayerUiState`. Add `cycleSpeed()` and `setSpeed(speed: Float)` methods to `PlayerViewModel`.
3. **Speed presets**: Define `val SPEED_OPTIONS = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)` in the ViewModel companion.
4. **UI**: Render a `SpeedChip` composable in the bottom controls row (between the skip buttons and the play/pause cluster). On D-pad centre press, cycle to the next speed. On long-press (detected via `pointerInput` with `detectTapGestures(onLongPress = ...)`), open a vertical `SpeedPickerPanel`.
5. **Live guard**: The ViewModel's `cycleSpeed` / `setSpeed` functions no-op when `isLive == true`.
6. **Fire Stick performance**: At 2x on Fire Stick, ExoPlayer may need a larger buffer to avoid under-runs. Set `LoadControl.Builder().setBufferDurationsMs(...)` with a 50% increase at speeds above 1.5x, if testing reveals stutter.

## Priority

**P2 -- Medium** (quality-of-life enhancement for VOD binge-watchers)

## Effort Estimate

**1-2 days**
- 0.5 day: ViewModel speed logic + ExoPlayer integration
- 0.5 day: SpeedChip composable + cycle/long-press interaction
- 0.5 day: SpeedPickerPanel + D-pad navigation
- 0.5 day: testing on Fire Stick at various speeds, buffer tuning
