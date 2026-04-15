# 37 - Stream Buffer Settings (Configurable Buffer Size for Slow Networks)

## Current State

The app uses Media3 ExoPlayer (`media3.exoplayer`, `media3.exoplayer.hls`) for playback. The player is instantiated with default buffer settings -- ExoPlayer's defaults are 50s max buffer, 2.5s min buffer for playback start, and 5s rebuffer threshold. There are no user-facing controls to adjust buffer sizes. The project memory notes that Fire Stick has strict RAM budgets (~200 MiB for mpv buffers) and that certain buffer configurations do not work on Fire OS.

## Gap

IPTV streams vary enormously in quality and reliability. Users on slow or unstable WiFi connections experience frequent buffering with default settings. Conversely, aggressive buffering on a Fire Stick with limited RAM (1-2 GB total) can cause OOM crashes or starve other processes. Users need the ability to tune the trade-off between buffer size (smoothness) and memory usage (stability).

## User Story

**As a user on a slow WiFi connection**, I want to increase the stream buffer size so that playback is smoother, and I want the app to warn me if the buffer is too large for my Fire Stick model.

## Acceptance Criteria

1. A new "Playback" section in Settings exposes buffer configuration.
2. User can select a buffer profile: "Low" (small buffer, fast start), "Standard" (ExoPlayer defaults), "High" (large buffer, smooth playback), "Custom".
3. "Custom" mode allows setting: min buffer (seconds), max buffer (seconds), buffer for playback start (seconds), rebuffer threshold (seconds).
4. Profiles map to concrete values:
   - Low: 15s max, 1s start, 2s rebuffer
   - Standard: 50s max, 2.5s start, 5s rebuffer
   - High: 120s max, 5s start, 10s rebuffer
5. A RAM usage estimate is shown for each profile based on approximate bitrate (e.g., "~80 MiB at 5 Mbps").
6. A warning is displayed if the estimated buffer exceeds 150 MiB: "This may cause instability on Fire Stick devices with limited RAM."
7. Buffer settings are applied to all new player instances immediately (no app restart required).
8. Settings persist across app restarts (SharedPreferences / DataStore).
9. The player screen shows a subtle buffer indicator (e.g., buffer health bar) during playback.
10. All controls are D-pad navigable with clear focus indicators.

## Technical Approach

- **ExoPlayer configuration**: Use `DefaultLoadControl.Builder()` to set `setBufferDurationsMs()` with user-selected values. Pass the custom `LoadControl` when building `ExoPlayer.Builder()`.
- **Storage**: Store the selected profile and custom values in `SharedPreferences` via a `PlaybackPreferences` class injected by Hilt.
- **Player integration**: The player builder (wherever `ExoPlayer.Builder` is called) reads from `PlaybackPreferences` at construction time. Since ExoPlayer instances are typically created per-playback, changes take effect on next play.
- **RAM estimation**: Approximate using `maxBufferSeconds * averageBitrate / 8`. Display as MiB. Use a conservative 5 Mbps default for the estimate.
- **Fire Stick safety**: Enforce a hard cap of 200 MiB estimated buffer. If custom values exceed this, show a warning and allow the user to proceed at their own risk.
- **UI**: New Settings section with a `DropdownMenu`-style selector for profiles. For "Custom", expand to show individual `Slider` or step-increment controls for each parameter.

## Priority

**Medium** -- Directly addresses the Fire Stick RAM budget concern documented in project memory. Important for users with slow networks.

## Effort Estimate

**1-2 days**

- Day 1: `PlaybackPreferences` class, ExoPlayer `LoadControl` integration, buffer profiles
- Day 2: Settings UI (profile selector, custom sliders, RAM estimate, warning), persistence
