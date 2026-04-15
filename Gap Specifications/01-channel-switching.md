# 01 - Channel Up/Down Switching While Watching

## Current State

The player (`PlayerScreen.kt`) handles D-pad key events as follows:
- **Up/Down**: Not handled at all -- fall through to the generic `showControls()` path.
- **Left/Right**: Seek backward 10s / forward 30s (VOD only; no-op for live via `seekRelative`).
- **Center/Enter**: Toggle play/pause.
- **Back**: Exit player, return to guide.

The player is initialised with a single `streamUrl` and `title` via `PlayerViewModel.initialize()`. It has no concept of "the channel list" or "current position within a channel list". The `PlayerViewModel` owns one `ExoPlayer` instance and sets a single `MediaItem` -- there is no playlist or channel-aware navigation.

`LiveScreen` passes a `PlayerArgs` to the nav state when a channel row is clicked, but does not pass the surrounding channel list or the current index.

## Gap

There is no way to switch channels while watching live TV. The user must press Back to return to the TV Guide, scroll to a new channel, and press Select -- a minimum of 3 key presses and a full screen transition for what should be a single Up/Down press.

This is the single most critical missing feature for a live TV experience. Every set-top box, smart TV app, and IPTV player supports channel up/down during playback.

## User Story

As a user watching live TV, I want to press D-pad Up or Down to switch to the previous or next channel instantly, so I can channel-surf without leaving the player.

## Acceptance Criteria

- [ ] Pressing D-pad Up while watching a live channel switches to the previous channel in the current list order.
- [ ] Pressing D-pad Down while watching a live channel switches to the next channel in the current list order.
- [ ] The channel switch occurs within the same ExoPlayer instance (no screen transition, no compose recomposition flicker).
- [ ] The channel list wraps: Up on the first channel goes to the last; Down on the last goes to the first.
- [ ] Channel switching works for both "All channels" and category-filtered lists (the list context from the guide is preserved).
- [ ] D-pad Up/Down continue to do nothing (or show controls) for VOD content -- only live channels get this behaviour.
- [ ] The `ChannelDao.markWatched()` is called each time a channel switch occurs so last-watched timestamps stay accurate.
- [ ] Channel switching does not cause a memory leak (no stacking of ExoPlayer instances).

## Technical Approach

### Key Design Decision: Channel List in PlayerViewModel

The player currently has no awareness of a channel list. Two approaches:

**Option A (Recommended): Pass the channel list and index to the player.**

- Extend `PlayerArgs` in `AppNav.kt` to include:
  ```kotlin
  data class PlayerArgs(
      ...
      val channelList: List<ChannelPlayInfo>? = null,  // null = VOD, non-null = live channel surfing
      val channelIndex: Int = 0,
  )
  
  data class ChannelPlayInfo(
      val contentId: String,
      val streamUrl: String,
      val title: String,
      val logoUrl: String,
      val channelNumber: Int?,
      val nowTitle: String?,
  )
  ```
- `PlayerViewModel` gains `switchChannel(delta: Int)` which:
  1. Computes new index (wrapping).
  2. Calls `player.setMediaItem(MediaItem.fromUri(newUrl))` then `player.prepare()`.
  3. Updates internal title/streamUrl/artworkUrl fields.
  4. Emits the new channel info to the UI state so the overlay updates.
- `PlayerScreen.onPreviewKeyEvent` adds:
  ```kotlin
  KeyEvent.KEYCODE_DPAD_UP -> { viewModel.switchChannel(-1); true }
  KeyEvent.KEYCODE_DPAD_DOWN -> { viewModel.switchChannel(+1); true }
  ```
- `LiveScreen` builds the `ChannelPlayInfo` list from the current filtered `state.channels` and passes it along with the clicked index.

**Option B: Shared singleton channel repository.**

A `LiveChannelRepository` singleton holds the current channel list and index. The player reads from it. This decouples the player from nav args but adds a singleton dependency and lifecycle complexity. Not recommended for v1 of this feature.

### Files to Modify

| File | Change |
|------|--------|
| `ui/nav/AppNav.kt` | Add `channelList` and `channelIndex` to `PlayerArgs`; add `ChannelPlayInfo` data class. |
| `ui/player/PlayerViewModel.kt` | Add `switchChannel(delta)`, track `channelList` and `currentIndex`, update ExoPlayer MediaItem in-place. |
| `ui/player/PlayerScreen.kt` | Handle `KEYCODE_DPAD_UP` / `KEYCODE_DPAD_DOWN` in the key event handler (only when `isLive`). |
| `ui/live/LiveScreen.kt` | Build `ChannelPlayInfo` list from filtered channels when launching the player. |
| `ui/live/LiveViewModel.kt` | Expose the filtered channel list as a stable snapshot for the player to consume. |

### Fire Stick Constraints

- ExoPlayer's `setMediaItem` + `prepare` on a single instance is fast and does not allocate a new codec -- the existing hardware decoder is reused. This is critical on Fire Stick where codec allocation is slow (~500ms).
- The channel list should be passed as a `List` (not a `Flow`) to avoid recomposition of the player during channel switches.

## Priority

**Critical** -- This is the #1 expected feature for any live TV app. Without it, the experience feels like a video player with a menu, not a TV.

## Effort Estimate

**Medium (half day)** -- The plumbing through PlayerArgs and the ViewModel is straightforward. The main work is ensuring seamless ExoPlayer source switching without flicker and handling edge cases (buffering state reset, error recovery on failed channel switch).
