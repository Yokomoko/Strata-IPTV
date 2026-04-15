# 14 - Channel Sort Order Options

## Current State

`LiveViewModel.refreshGuide()` sorts channels by:
```kotlin
result.sortWith(compareBy<ChannelWithGuide> {
    it.channelNumber ?: Int.MAX_VALUE
}.thenBy { it.displayName.lowercase() })
```

This gives: channels with Sky numbers first (sorted by number), then channels without numbers (sorted alphabetically by name).

There is no way for the user to change the sort order. The `SkyChannelNumbers` map covers ~60 UK channels; the remaining channels (which could be hundreds in a large IPTV playlist) are sorted alphabetically, which may not be the user's preferred order.

## Gap

Users cannot sort channels by:
- Alphabetical (A-Z)
- Channel number (current default, but only meaningful for channels with assigned numbers)
- Most recently watched
- Most watched (frequency)
- Custom order (manual drag-and-drop reordering)

## User Story

As a user, I want to change how channels are sorted in the TV Guide, so I can find channels faster based on how I use them.

## Acceptance Criteria

- [ ] A sort button appears in the TV Guide header (next to the existing grid/list toggle).
- [ ] Pressing the sort button cycles through sort options: "Channel Number" (default) -> "A-Z" -> "Recently Watched" -> "Most Watched".
- [ ] The currently active sort is indicated (text label on the button).
- [ ] "Recently Watched" sorts by `ChannelEntity.last_watched` (most recent first), with unwatched channels at the bottom.
- [ ] "Most Watched" sorts by watch frequency from `WatchHistoryEntity` (requires a count query).
- [ ] The selected sort preference persists across app restarts (stored in SharedPreferences or DataStore).
- [ ] Sort changes are instant (no loading indicator) -- it's an in-memory re-sort of the existing list.

## Technical Approach

### Sort Enum

```kotlin
enum class ChannelSortOrder {
    CHANNEL_NUMBER,  // Default: Sky number, then alpha
    ALPHABETICAL,    // A-Z by display name
    RECENTLY_WATCHED, // By last_watched DESC
    MOST_WATCHED,    // By watch count DESC
}
```

### LiveViewModel Changes

- Add `_sortOrder: MutableStateFlow<ChannelSortOrder>` with persistence via DataStore.
- Modify the `combine` in `state` to apply the sort:
  ```kotlin
  combine(_channels, _categories, _selectedCategory, _sortOrder) { ... }
  ```
- For "Most Watched", query `WatchHistoryDao` for a count-by-contentId and join in-memory.

### Files to Modify

| File | Change |
|------|--------|
| `ui/live/LiveViewModel.kt` | Add `_sortOrder`, sort logic, persistence. |
| `ui/live/LiveScreen.kt` | Add sort button to header, display current sort label. |
| `data/db/Daos.kt` | Add `watchCountByContentId()` query to `WatchHistoryDao` for "Most Watched" sort. |

## Priority

**Low** -- The default channel-number + alphabetical sort works well for most users. Advanced sorting is a convenience feature.

## Effort Estimate

**Small (1-2h)** -- In-memory sorting with different comparators is trivial. The "Most Watched" sort requires one additional DAO query. DataStore persistence for the preference is boilerplate.
