# 16 - Progress Bar with Thumbnail Preview

## Current State

The VOD progress bar in `PlayerScreen.kt` is a simple `Box`-based fill indicator (`Modifier.fillMaxWidth(progress)`) with elapsed/remaining time labels. It updates based on `player.currentPosition` / `player.duration`. There is no seek-scrubbing interaction -- the user can only seek via D-pad left/right in fixed -10s/+30s increments. There is no thumbnail preview, no scrub handle, and no frame-accurate position preview.

The progress bar is not interactive (no click/touch handling, no D-pad focus on the bar itself).

## Gap

Premium video players show a thumbnail preview bubble above the progress bar when the user scrubs, giving visual context of where they are seeking to. On a D-pad remote, the equivalent is showing a preview when holding left/right to fast-seek. Without this, users seeking through a 2-hour movie have no visual anchor and must rely on the time counter alone, making it difficult to find a specific scene.

## User Story

> As a VOD viewer, I want to see thumbnail previews when I scrub through the progress bar so that I can quickly find the scene I am looking for.

## Acceptance Criteria

1. The progress bar gains a visible seek handle (a small circle or pill at the current position).
2. When the user holds D-pad Left or D-pad Right for more than 500ms, the player enters "scrub mode": the seek increment accelerates (10s -> 30s -> 60s -> 120s the longer held) and a thumbnail preview bubble appears above the seek handle showing a frame from the target position.
3. The thumbnail preview updates in near-real-time as the user continues to hold the direction key.
4. Releasing the direction key confirms the seek and the preview dismisses.
5. A timestamp label inside the preview bubble shows the target position.
6. For streams without thumbnail support (most IPTV), the preview bubble shows only the timestamp and a progress indicator within the episode (e.g. "45:30 / 1:32:00") with no thumbnail image.
7. The progress bar expands in height (4dp to 8dp) during scrub mode for better visibility.

## Technical Approach

1. **Accelerating seek**: In `PlayerScreen.kt`'s `onPreviewKeyEvent`, detect D-pad Left/Right `ACTION_DOWN` events held continuously. Track hold duration with a coroutine-based timer. Map hold duration to seek delta: 0-1s = 10s, 1-3s = 30s, 3-6s = 60s, 6s+ = 120s per tick (tick every 200ms).
2. **Scrub mode state**: Add `isScrubbing: Boolean`, `scrubTargetMs: Long` to `PlayerUiState`. During scrub mode, update `scrubTargetMs` without actually seeking the player -- only seek on key release to avoid decoder thrashing.
3. **Thumbnail generation** (future/optional): For HLS streams with I-frame playlists (`EXT-X-I-FRAMES-ONLY`), use Media3's `BitmapLoader` or a secondary lightweight `ExoPlayer` instance seeking to the target position and extracting a frame via `ImageReader`. This is expensive and should be opt-in.
4. **Fallback preview**: For most IPTV streams, show a timestamp-only preview bubble: a rounded rectangle with `StrataColors.SurfaceFloat` background containing the target timestamp in large text and a mini progress bar.
5. **Seek handle**: Replace the current bare progress bar with a custom `Canvas`-drawn bar that includes a draggable dot (the "handle") at the current/scrub position. The handle pulses with `StrataColors.AccentPrimary` glow during scrub mode.
6. **Bar expansion**: Animate the progress bar height from 4dp to 8dp using `animateDpAsState` when `isScrubbing == true`.
7. **Preview bubble**: A `PreviewBubble` composable positioned above the seek handle using `Modifier.offset`. It shows the target time and, when available, a `Bitmap` thumbnail in a 16:9 `Image` composable.

## Priority

**P2 -- Medium** (significant UX improvement for long-form VOD; timestamp-only mode provides value even without thumbnails)

## Effort Estimate

**3-5 days**
- 1 day: accelerating seek logic + scrub mode state in ViewModel
- 1 day: custom progress bar with seek handle + expansion animation
- 0.5 day: timestamp-only PreviewBubble composable
- 1-2 days: optional thumbnail extraction (HLS I-frame or decoder snapshot approach) + memory management on Fire Stick
- 0.5 day: testing with various stream types + D-pad hold behaviour tuning
