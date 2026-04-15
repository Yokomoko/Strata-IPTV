# 14 - Skip Intro / Skip Recap

## Current State

The player has no concept of intro or recap segments. There are no timestamp markers in the data model (`EpisodeEntity` has `resume_position_ms` but no intro/outro markers). The controls overlay does not display any skip buttons beyond the standard -10s/+30s seek. No chapter or segment metadata is parsed from streams or stored locally.

## Gap

Netflix's "Skip Intro" button is one of the most-loved UX features in streaming. For series content, intros (title sequences) and recaps ("previously on...") are repetitive segments that viewers want to skip. Without this feature, users must manually fast-forward 30 seconds at a time, which is particularly clunky on a D-pad remote.

## User Story

> As a series viewer, I want a "Skip Intro" button to appear during title sequences so that I can jump straight to the content without tedious manual seeking.

## Acceptance Criteria

1. When the player detects it is within a skip-eligible segment, a "Skip Intro" or "Skip Recap" pill button appears in the bottom-right of the screen, above the progress bar.
2. The button is D-pad focusable and auto-focuses when it appears.
3. Pressing the button seeks to the end of the segment. The button disappears with a fade-out.
4. If the user does not press the button, it auto-hides after 8 seconds or when the segment naturally ends.
5. Skip markers can be sourced from: (a) manual per-series configuration in Settings, (b) a `skip_segments` JSON file bundled with the playlist, or (c) heuristic detection (fixed offset, e.g. "first 90 seconds" configurable per series).
6. A Settings screen allows the user to set a default "auto-skip intro" preference that automatically seeks past the intro without requiring the button press.

## Technical Approach

1. **Data model**: Add a new Room entity `SkipSegmentEntity` with columns: `series_title`, `season_number` (nullable for series-wide defaults), `segment_type` ("intro" | "recap" | "outro"), `start_ms`, `end_ms`. Add a corresponding `SkipSegmentDao`.
2. **Heuristic fallback**: If no explicit segments exist for a series, apply a configurable default: intro = 0ms-90000ms for episode numbers > 1. Expose this default in Settings as "Default intro length" (30s / 60s / 90s / 120s / Off).
3. **Segment detection**: In `PlayerViewModel`, add a `LaunchedEffect`-driven coroutine that polls `player.currentPosition` every 500ms (or uses `Player.Listener.onPositionDiscontinuity` for efficiency). When the position enters a skip segment's range, set `skipSegment: SkipSegmentInfo?` in `PlayerUiState`.
4. **Skip action**: `skipCurrentSegment()` in ViewModel calls `player.seekTo(segment.endMs)` and clears the `skipSegment` state.
5. **UI**: A `SkipButton` composable rendered inside the player `Box` at `Alignment.BottomEnd` with `padding(end = 24.dp, bottom = 100.dp)`. Uses `AnimatedVisibility` with `fadeIn`/`fadeOut`. The button uses `StrataColors.AccentPrimary` background with white text for high visibility.
6. **Auto-skip**: If `auto_skip_intro` preference is enabled in DataStore, the ViewModel automatically calls `skipCurrentSegment()` after a 1-second grace period (so the user sees a brief flash of "Skipping..." text).
7. **Future: server-side markers**: Design the `SkipSegmentEntity` schema to accept imports from a future API (e.g. playlist provider metadata, community-sourced skip databases).

## Priority

**P3 -- Low** (high user delight but requires per-series data; heuristic fallback provides partial value)

## Effort Estimate

**3-5 days**
- 1 day: `SkipSegmentEntity` + DAO + migration
- 1 day: segment detection logic in ViewModel + polling/listener approach
- 0.5 day: SkipButton composable + animation + D-pad focus
- 0.5 day: Settings UI for default intro length + auto-skip toggle
- 1-2 days: heuristic tuning, per-series configuration UI, edge cases (seeking past a segment, multiple segments in one episode)
