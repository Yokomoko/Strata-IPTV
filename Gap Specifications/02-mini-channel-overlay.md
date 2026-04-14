# 02 - Mini Channel Info Overlay (OSD Banner)

## Current State

The player's controls overlay (`PlayerScreen.kt`) shows:
- Top bar: back arrow, stream title, "LIVE" badge.
- Bottom bar: play/pause button (and seek buttons for VOD).
- Auto-hides after 4 seconds via `restartHideTimer()`.

When watching live TV, the title displayed is just the channel's `displayName` (e.g., "BBC One"). There is no channel number, no channel logo, no now/next programme information, and no indication of what channel was just switched to.

The controls overlay is a full-screen gradient overlay designed for VOD transport controls -- it is not the compact "channel banner" that TV viewers expect when switching channels.

## Gap

When a user switches channels (see Gap 01), there is no brief informational banner showing:
- Channel number and name
- Channel logo
- Current programme title and time remaining
- Next programme title

Every TV platform (Sky, Virgin, Freeview, Samsung TV Plus, Pluto TV) shows a compact OSD banner on channel switch that auto-dismisses after 3-5 seconds. This banner is distinct from the full controls overlay.

## User Story

As a user, when I switch to a new channel, I want to see a brief overlay showing the channel number, name, logo, and what's currently on, so I know what I'm watching without having to go back to the guide.

## Acceptance Criteria

- [ ] A compact banner appears at the bottom of the screen when a channel switch occurs.
- [ ] The banner shows: channel logo, channel number, channel name, NOW programme title, NOW programme time remaining, NEXT programme title.
- [ ] The banner auto-hides after 4 seconds.
- [ ] The banner slides in from the bottom with a smooth animation (slide + fade).
- [ ] Pressing any D-pad direction while the banner is visible resets the auto-hide timer.
- [ ] Pressing D-pad Up/Down while the banner is visible triggers another channel switch and updates the banner content (no dismiss-then-reshow flicker).
- [ ] The banner does NOT appear for VOD content -- only live channels.
- [ ] The banner is visually distinct from the full transport controls overlay (smaller, bottom-anchored, no gradient across the full screen).
- [ ] Pressing Info/Guide (if available via CEC) also shows the banner.
- [ ] The banner works correctly when the main controls overlay is hidden.

## Technical Approach

### New Composable: `ChannelBanner`

Create a new composable within `PlayerScreen.kt` (or extracted to `ui/player/ChannelBanner.kt`):

```kotlin
@Composable
fun ChannelBanner(
    channelInfo: ChannelBannerInfo?,  // null = hidden
    modifier: Modifier = Modifier,
)
```

The banner is a horizontal strip at the bottom of the screen:
```
+------------------------------------------------------------------+
|  [Logo]  101  BBC One  |  BBC News at Six  (23 min left)  |  NEXT: The Repair Shop  |
+------------------------------------------------------------------+
```

### State Management

- Add to `PlayerUiState`:
  ```kotlin
  data class PlayerUiState(
      ...
      val channelBanner: ChannelBannerInfo? = null,  // null = hidden
  )
  
  data class ChannelBannerInfo(
      val logoUrl: String,
      val channelNumber: Int?,
      val channelName: String,
      val nowTitle: String?,
      val nowEndTime: Instant?,
      val nextTitle: String?,
  )
  ```
- `PlayerViewModel.switchChannel()` sets `channelBanner` to the new channel's info and starts a 4-second dismiss timer (similar to `restartHideTimer`).
- A separate `bannerHideJob` manages the auto-dismiss independently from the controls overlay.

### Animation

Use `AnimatedVisibility` with `slideInVertically` + `fadeIn` for the enter and `slideOutVertically` + `fadeOut` for the exit. The banner renders in a `Box(Modifier.align(Alignment.BottomCenter))` inside the player's root container.

### Files to Modify

| File | Change |
|------|--------|
| `ui/player/PlayerScreen.kt` | Add `ChannelBanner` composable, wire to UI state. |
| `ui/player/PlayerViewModel.kt` | Add `ChannelBannerInfo` to `PlayerUiState`, add banner show/hide logic with its own timer. |
| `ui/nav/AppNav.kt` | Ensure `ChannelPlayInfo` includes `nowTitle`, `nowEndTime`, `nextTitle`, `channelNumber`, `logoUrl`. |

### Design Notes

- The banner background should be a semi-transparent dark surface (`StrataColors.SurfaceBase.copy(alpha = 0.9f)`) with rounded top corners.
- The "time remaining" should be computed live from `nowEndTime` using a `LaunchedEffect` that ticks every minute.
- The banner must not interfere with the full controls overlay -- if the user presses Center to bring up full controls, the banner should dismiss.

## Priority

**Critical** -- This is inseparable from channel switching (Gap 01). Switching channels without visual feedback is disorienting.

## Effort Estimate

**Small (1-2h)** -- Straightforward Compose UI work once the channel info is available from Gap 01. The animation and timer patterns already exist in the controls overlay.
