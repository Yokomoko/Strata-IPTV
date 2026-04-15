# 07 - Quick Channel List Overlay While Watching

## Current State

While watching live TV, the user's only navigation option is:
- D-pad Up/Down: channel switch (after Gap 01).
- Back: exit to guide.
- Center: play/pause.

There is no way to browse a list of channels while the current channel continues playing in the background. The user must fully exit the player, return to the TV Guide, browse channels, and select one.

## Gap

Premium TV experiences (Sky Q, Virgin TV 360, Apple TV) provide a quick-access mini channel list that overlays on top of the live video. The user presses a key (typically Guide or Up when the banner is visible) and a compact scrollable channel strip appears on the left or bottom, showing nearby channels with now/next info. The current channel continues playing underneath.

This is distinct from:
- The full TV Guide (a separate screen with the sidebar).
- The channel banner (Gap 02, a brief info display).
- Channel up/down (Gap 01, blind switching).

## User Story

As a user watching live TV, I want to bring up a quick channel list overlay so I can browse channels visually while still watching the current programme, then select one to switch to.

## Acceptance Criteria

- [ ] Pressing the Guide key (`KEYCODE_GUIDE` or `KEYCODE_PROG_RED` or a configured key) while watching live TV shows a semi-transparent channel list overlay on the left side of the screen.
- [ ] The overlay shows a vertically scrollable list of channels, each with: logo, channel number, name, and NOW programme title.
- [ ] The currently-playing channel is highlighted in the list.
- [ ] D-pad Up/Down scrolls through the channel list without changing the playing channel.
- [ ] Pressing Select on a channel in the list switches to that channel and dismisses the overlay.
- [ ] Pressing Back dismisses the overlay without changing channels.
- [ ] The overlay auto-hides after 10 seconds of inactivity.
- [ ] The current video continues playing (at reduced opacity or with a dark scrim) behind the overlay.
- [ ] The overlay supports category filtering via Left/Right on a top chips row (same categories as the guide).
- [ ] The overlay animates in from the left with a slide transition.

## Technical Approach

### Overlay Architecture

The quick channel list is rendered inside `PlayerScreen.kt` as a conditional overlay, NOT as a separate nav destination. This ensures the ExoPlayer continues playing underneath.

```kotlin
// Inside PlayerScreen's root Box:
AnimatedVisibility(
    visible = state.quickListVisible,
    enter = slideInHorizontally(initialOffsetX = { -it }) + fadeIn(),
    exit = slideOutHorizontally(targetOffsetX = { -it }) + fadeOut(),
) {
    QuickChannelList(
        channels = channelList,
        currentIndex = currentChannelIndex,
        onSelectChannel = { index -> viewModel.switchToIndex(index) },
        onDismiss = { viewModel.hideQuickList() },
    )
}
```

### QuickChannelList Composable

A `Column` taking roughly 40% of screen width on the left side:
- Top: Category chips row (horizontal).
- Body: `TvLazyColumn` of compact channel rows.
- Background: `StrataColors.SurfaceVoid.copy(alpha = 0.92f)`.
- Right edge: subtle gradient fade into the video.

Each row is a compact version of the guide's `ChannelRow`:
```
[Logo] 101 BBC One
       NOW: BBC News at Six
```

### Focus Management

This is the trickiest part. When the overlay is visible:
- Focus must move into the `TvLazyColumn` inside the overlay.
- D-pad Right should dismiss the overlay (back to video).
- D-pad Left on the leftmost column should do nothing (no escape to sidebar).
- The `TvLazyColumn` should initially focus on the currently-playing channel.

Use a `FocusRequester` on the overlay's first item and request focus when the overlay becomes visible.

### Key Mapping

In `PlayerScreen.onPreviewKeyEvent`, add:
```kotlin
KeyEvent.KEYCODE_GUIDE,
KeyEvent.KEYCODE_TV_INPUT,
KeyEvent.KEYCODE_PROG_RED -> {
    if (isLive) { viewModel.toggleQuickList(); true }
    else false
}
```

If no Guide key is available on the Fire Stick remote, repurpose the "three-line" menu button (when not in favourites mode) or use a double-tap of D-pad Up.

### State

Add to `PlayerUiState`:
```kotlin
val quickListVisible: Boolean = false,
```

### Files to Modify

| File | Change |
|------|--------|
| `ui/player/PlayerScreen.kt` | Add `QuickChannelList` composable, handle Guide key, manage overlay visibility. |
| `ui/player/PlayerViewModel.kt` | Add `toggleQuickList()`, `hideQuickList()`, `quickListVisible` state. |
| `ui/player/QuickChannelList.kt` | (New file) Extracted composable for the channel list overlay. |

### Fire Stick Constraints

- The overlay must be lightweight. The channel list should reuse the same `ChannelPlayInfo` list already in memory (from Gap 01), not trigger a new database query.
- The scrim over the video area should be a simple `Box` with a semi-transparent background, not a blur (blur shaders are expensive on Fire Stick's GPU).
- Limit the visible channel count to ~8-10 rows to keep the composable tree small.

## Priority

**Medium** -- This is a "nice to have" that significantly improves the experience for users with large channel lists (100+). It sits between channel up/down (fast but blind) and the full guide (powerful but slow to access).

## Effort Estimate

**Large (1+ day)** -- The composable itself is moderate work, but the focus management between the overlay and the video player is tricky on Android TV. Testing D-pad navigation edge cases (focus escaping the overlay, focus returning to video on dismiss) will take time.
