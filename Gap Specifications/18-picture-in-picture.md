# 18 - Picture-in-Picture Mode

## Current State

The player is rendered as a full-screen overlay in `Shell.kt` -- when `playerArgs != null`, the `PlayerScreen` composable is the only thing rendered (`return` after the player block prevents the rest of the shell from drawing). There is no PiP implementation. The `AndroidManifest.xml` does not declare `android:supportsPictureInPicture="true"`. The `PlayerViewModel` owns the `ExoPlayer` instance and releases it in `onCleared`.

## Gap

Picture-in-Picture allows the user to continue watching (especially live TV) while browsing the EPG, movie library, or show details. This is a natural workflow on Fire TV where users frequently channel-surf. Without PiP, every navigation away from the player stops playback entirely and requires re-entering the stream.

## User Story

> As a live TV viewer, I want to shrink the player to a corner while I browse the guide or movie library so that I can keep watching while deciding what to watch next.

## Acceptance Criteria

1. When the user presses a designated button (e.g. D-pad Down while controls are visible, or a dedicated "Minimize" icon button), the player enters PiP mode: a small (320x180 dp) floating window in the bottom-right corner of the screen.
2. The rest of the shell UI (sidebar + content) renders underneath and is fully navigable.
3. The PiP window shows the video surface with no overlaid controls.
4. Pressing D-pad Up or Select while PiP is focused returns to full-screen player mode.
5. Pressing Back while PiP is focused closes the player entirely (stops playback, saves position).
6. Audio continues playing during PiP.
7. PiP works for both live and VOD content.
8. The PiP window has a subtle border using `StrataColors.AccentPrimary` to distinguish it from the background content.

## Technical Approach

### Option A: Android System PiP (Recommended for Fire TV)

1. **Manifest**: Add `android:supportsPictureInPicture="true"` and `android:configChanges="screenSize|smallestScreenSize|screenLayout|orientation"` to the main Activity.
2. **Enter PiP**: Call `activity.enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build())` from the ViewModel (via an Activity callback interface).
3. **Detect PiP mode**: Override `Activity.onPictureInPictureModeChanged()` and propagate `isInPipMode: Boolean` through a shared state holder (e.g. a `PipStateHolder` singleton provided by Hilt).
4. **UI adaptation**: When `isInPipMode == true`, the `PlayerScreen` composable hides all overlays (controls, buffering spinner text, error overlay) and renders only the `AndroidView(PlayerView)`.
5. **Remote actions**: Register `RemoteAction` intents for play/pause and close via `PictureInPictureParams.Builder().setActions(...)`.
6. **Exit PiP**: When the user taps the PiP window to expand, the Activity returns to full-screen and the controls overlay reappears.

### Option B: In-App Floating Window (Fallback)

If system PiP proves unreliable on Fire OS (some Fire TV builds have PiP quirks), implement an in-app composable-based approach:
1. Restructure `Shell.kt` to render the `PlayerView` in a `Box` with animated size (`animateDpAsState`) that shrinks to 320x180 dp and repositions to `Alignment.BottomEnd`.
2. The shell content renders below the player `Box` (remove the `return` guard).
3. This requires the `ExoPlayer` instance to survive the composable lifecycle change -- already handled since the ViewModel owns it.

### Shared concerns

- **Fire Stick RAM**: The player + shell UI rendering simultaneously will increase memory usage. Monitor via `Debug.getNativeHeapAllocatedSize()` to ensure we stay within the ~200 MiB budget.
- **Focus management**: When entering PiP, focus must transfer to the shell content. When exiting PiP, focus returns to the player.

## Priority

**P3 -- Low** (nice-to-have; complex implementation with Fire OS PiP quirks; in-app approach is more reliable but architecturally invasive)

## Effort Estimate

**4-6 days**
- 1 day: system PiP integration (manifest, enter/exit, Activity callbacks)
- 1 day: UI adaptation for PiP mode (hide overlays, remote actions)
- 1 day: focus management + shell co-rendering
- 1-2 days: Fire OS PiP compatibility testing + fallback to in-app approach if needed
- 0.5-1 day: memory profiling on Fire Stick to verify RAM budget compliance
