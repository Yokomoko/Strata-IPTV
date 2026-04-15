# 03 - Favourite Channels

## Current State

The database layer has full favourite support already built:

- **`ChannelEntity`** has `is_favourite: Boolean` column (default `false`).
- **`ChannelDao`** has:
  - `watchFavourites(): Flow<List<ChannelEntity>>` -- returns only favourited channels.
  - `setFavourite(contentId: String, fav: Boolean)` -- toggles the flag.
- **`FavouriteEntity`** and **`FavouriteDao`** exist as a general-purpose favourites table (used for movies/shows), with `add()`, `remove()`, `watchAll()`, `watchIsFavourite()`.

However, **none of this is wired to the UI**:

- `LiveScreen` has no favourite toggle button on channel rows.
- `LiveScreen` has no "Favourites" category chip (category chips come from M3U group titles, not from the `is_favourite` flag).
- `LiveViewModel` does not inject `ChannelDao.setFavourite` or expose favourite state.
- There is no visual indicator (heart icon, star) on channel rows showing favourite status.

## Gap

Users cannot mark channels as favourites or filter the TV Guide to show only their favourite channels. The database schema is ready but the feature has zero UI integration.

## User Story

As a user, I want to mark my favourite channels with a heart icon and filter the TV Guide to show only my favourites, so I can quickly find the channels I watch most.

## Acceptance Criteria

- [ ] Each channel row in the TV Guide displays a heart icon (filled when favourite, outlined when not).
- [ ] Long-pressing Select (or pressing a dedicated button like the Menu key) on a channel row toggles its favourite status.
- [ ] The favourite status persists across app restarts (stored in `ChannelEntity.is_favourite`).
- [ ] A "Favourites" chip appears in the category row -- always as the second chip after "All" (before M3U-derived categories).
- [ ] Selecting the "Favourites" chip filters the channel list to show only favourited channels.
- [ ] If no channels are favourited, the "Favourites" chip still appears but selecting it shows a helpful empty state ("No favourite channels yet. Long-press a channel to add it.").
- [ ] The favourite toggle is also accessible from the channel info banner while watching (Gap 02) -- pressing a designated key (e.g., Menu button) toggles favourite for the current channel.
- [ ] Toggling a favourite while the "Favourites" filter is active updates the list immediately (removing a favourite while viewing favourites removes the row with an animation).
- [ ] The `FavouriteEntity` table is also updated (in addition to `ChannelEntity.is_favourite`) so the general favourites system stays in sync.

## Technical Approach

### LiveViewModel Changes

- Inject `ChannelDao` (already present) and use `setFavourite()`.
- Add `toggleFavourite(contentId: String)` method that:
  1. Reads current `is_favourite` from the local `_channels` list.
  2. Calls `channelDao.setFavourite(contentId, !current)`.
  3. Also calls `favouriteDao.add()` or `favouriteDao.remove()` to keep the general table in sync.
  4. Triggers `refreshGuide()` so the UI updates.
- Modify the categories list construction to always prepend "Favourites" after "All".
- Modify the category filter logic: when `selected == "Favourites"`, filter by `it.channelEntity.isFavourite`.

### LiveScreen Changes

- Add a small heart icon to `ChannelRow`, positioned at the right edge.
- Handle long-press on the channel `Surface`:
  ```kotlin
  Surface(
      onClick = onPlay,
      onLongClick = { onToggleFavourite(channel.channelEntity.contentId) },
      ...
  )
  ```
  Note: `tv-material3` `Surface` supports `onLongClick` via the `ClickableSurfaceDefaults` interaction. If not directly available, use `Modifier.combinedClickable` or a custom `onPreviewKeyEvent` that detects long-press of `KEYCODE_DPAD_CENTER`.

### ChannelWithGuide Changes

Add `isFavourite: Boolean` to the `ChannelWithGuide` data class, populated from `ChannelEntity.isFavourite`.

### Files to Modify

| File | Change |
|------|--------|
| `ui/live/LiveViewModel.kt` | Add `toggleFavourite()`, inject `FavouriteDao`, add "Favourites" to categories, update filter logic. |
| `ui/live/LiveScreen.kt` | Add heart icon to `ChannelRow`, handle long-press for favourite toggle. |
| `ui/live/LiveViewModel.kt` (model) | Add `isFavourite` to `ChannelWithGuide`. |
| `ui/player/PlayerViewModel.kt` | Add `toggleFavourite()` for toggling from the player banner (depends on Gap 02). |

### Visual Design

- Heart icon: `Icons.Outlined.FavoriteBorder` (not favourite) / `Icons.Filled.Favorite` (favourite).
- Heart colour: `StrataColors.StatusLive` (red) when favourite, `StrataColors.TextTertiary` when not.
- Position: right-aligned in the channel row, before the row's end padding.
- Size: 20dp, with a subtle scale animation on toggle.

## Priority

**High** -- Favourites are the foundation for favourite-channel zapping (Gap 04), which is the user's explicitly requested "run through favourites like Sky" feature.

## Effort Estimate

**Medium (half day)** -- The database layer is already built. Main work is the UI integration (heart icon, long-press handler, category filter logic) and ensuring the two favourite tables stay in sync.
