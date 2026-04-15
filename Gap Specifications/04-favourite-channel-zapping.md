# 04 - Favourite Channel Zapping (Sky-Style Favourites Cycling)

## Current State

Channel switching (once Gap 01 is implemented) will cycle through all channels in the current filtered list. There is no mode or mechanism to cycle through only favourite channels while watching.

Sky TV's remote has a dedicated "Fav" button that, when pressed during viewing, constrains channel up/down to cycle only through the user's favourite channels list. This is distinct from the guide filter -- it works while watching, without opening the guide.

## Gap

Even after Gap 01 (channel up/down) and Gap 03 (favourite channels UI) are implemented, there is no way to constrain channel surfing to favourites only while watching. The user would have to:
1. Go back to the guide.
2. Select the "Favourites" chip.
3. Pick a channel.
4. Channel up/down would then cycle through favourites (if the channel list context is preserved from the filter).

This is clunky. The user wants a toggle while watching: "from now on, up/down only cycles my favourites."

## User Story

As a user watching live TV, I want to toggle "favourites mode" so that D-pad Up/Down only cycles through my favourite channels, just like pressing the Fav button on a Sky remote.

## Acceptance Criteria

- [ ] A designated key (e.g., the Menu/Hamburger button on the Fire Stick remote, `KEYCODE_MENU`) toggles "favourites zapping mode" while watching.
- [ ] When favourites mode is active, D-pad Up/Down cycle through only the user's favourite channels (in channel-number order).
- [ ] A visual indicator appears on the channel banner (Gap 02) showing that favourites mode is active (e.g., a filled heart icon or "FAV" badge).
- [ ] Toggling favourites mode off returns to cycling through all channels.
- [ ] If the user has no favourites and tries to activate favourites mode, a brief toast/banner appears: "No favourite channels. Long-press a channel in the guide to add favourites."
- [ ] If the user is currently on a non-favourite channel and activates favourites mode, the first channel switch jumps to the nearest favourite channel.
- [ ] Favourites mode persists for the duration of the viewing session but resets when the user exits the player.
- [ ] The channel banner (Gap 02) updates to show the position within the favourites list (e.g., "3 of 12 favourites").

## Technical Approach

### PlayerViewModel State

- Add a `favouritesMode: Boolean` field to `PlayerUiState`.
- Add a separate `favouriteChannelList: List<ChannelPlayInfo>` built from the full channel list filtered by `isFavourite`.
- `switchChannel(delta)` checks `favouritesMode`:
  - If true, uses `favouriteChannelList` for navigation.
  - If false, uses the full `channelList`.
- Add `toggleFavouritesMode()`:
  ```kotlin
  fun toggleFavouritesMode() {
      if (favouriteChannelList.isEmpty()) {
          // Show "no favourites" toast
          return
      }
      _uiState.update { it.copy(favouritesMode = !it.favouritesMode) }
      // If switching to fav mode and current channel isn't a favourite,
      // jump to the nearest favourite.
  }
  ```

### Key Mapping

In `PlayerScreen.onPreviewKeyEvent`:
```kotlin
KeyEvent.KEYCODE_MENU -> {
    viewModel.toggleFavouritesMode()
    true
}
```

The Fire Stick remote's three-line "menu" button sends `KEYCODE_MENU`. This is an intuitive mapping since the button is otherwise unused during playback.

### Data Flow

- `ChannelPlayInfo` (from Gap 01) needs an `isFavourite: Boolean` field.
- When `PlayerArgs` is constructed in `LiveScreen`, each channel's favourite status is included.
- `PlayerViewModel` pre-computes `favouriteChannelList` on init by filtering `channelList` where `isFavourite == true`.

### Files to Modify

| File | Change |
|------|--------|
| `ui/player/PlayerViewModel.kt` | Add `favouritesMode` to state, add `toggleFavouritesMode()`, modify `switchChannel()` to respect mode. |
| `ui/player/PlayerScreen.kt` | Handle `KEYCODE_MENU`, show favourites mode indicator on banner. |
| `ui/nav/AppNav.kt` | Add `isFavourite` to `ChannelPlayInfo`. |
| `ui/live/LiveScreen.kt` | Include `isFavourite` when building `ChannelPlayInfo` list. |

### Edge Cases

- Favourite removed while in favourites mode: if the current channel is unfavourited (via the banner toggle from Gap 03), it should remain visible until the next channel switch, then be excluded from the cycle.
- Empty favourites list after removal: automatically deactivate favourites mode and show a brief message.

## Priority

**High** -- Explicitly requested by the user ("running through favourites like Sky TV does"). Depends on Gap 01 and Gap 03.

## Effort Estimate

**Small (1-2h)** -- Once Gap 01 (channel switching) and Gap 03 (favourites) exist, this is a filtering layer on top of the channel list plus a key mapping. The logic is simple; the main work is the UI indicator and edge case handling.
