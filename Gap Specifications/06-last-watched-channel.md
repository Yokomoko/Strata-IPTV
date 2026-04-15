# 06 - Last-Watched Channel Memory

## Current State

The `ChannelEntity` has a `last_watched: Instant?` column, and `ChannelDao` has a `markWatched(contentId, at)` method. However:

- `markWatched()` is never called anywhere in the codebase.
- The `LiveScreen` / `LiveViewModel` do not read `last_watched` to determine which channel to highlight or auto-play on launch.
- The `ContinueWatchingEntity` tracks live content (with `content_type = "live"`), but this is for the home screen's "Continue Watching" rail -- it does not drive auto-resume of the last live channel.
- When the app opens and the user navigates to the TV Guide, they always start at the top of the channel list with no channel pre-selected.

## Gap

There is no "resume last channel" behaviour. When the user opens the app (or returns to the Live section), they should be taken directly to their last-watched channel, or at minimum the guide should scroll to and highlight that channel.

This is how every set-top box works: power on -> resume last channel.

## User Story

As a user, when I open the app and go to the TV Guide (or launch the app configured to start on Live TV), I want to see my last-watched channel highlighted and optionally auto-playing, so I can resume watching immediately.

## Acceptance Criteria

- [ ] When a live channel starts playing, `channelDao.markWatched()` is called with the current timestamp.
- [ ] When the user navigates to the TV Guide, the channel list auto-scrolls to the last-watched channel (the one with the most recent `last_watched` timestamp).
- [ ] The last-watched channel row has a subtle visual distinction (e.g., a thin left-border accent or a "Last watched" label).
- [ ] (Optional, configurable) When the app starts and the user's preferred start screen is "TV Guide", auto-play the last-watched channel immediately (skip the guide, go straight to playback).
- [ ] If no channel has been watched yet, the guide starts at the top of the list as it does today.
- [ ] The last-watched channel is preserved across app restarts (it uses the database, not in-memory state).
- [ ] Channel switching (Gap 01) updates `last_watched` for each channel that is tuned to.

## Technical Approach

### Calling markWatched

- In `PlayerViewModel.initialize()`, when `isLive == true`, call `channelDao.markWatched(contentId, Instant.now())`.
- In `PlayerViewModel.switchChannel()`, call `channelDao.markWatched()` for the new channel.
- This requires `ChannelDao` to be injected into `PlayerViewModel`. Currently it only has `ContinueWatchingDao` and `WatchHistoryDao`.

### LiveViewModel: Scroll to Last Watched

- Add a new query to `ChannelDao`:
  ```kotlin
  @Query("SELECT * FROM channels WHERE last_watched IS NOT NULL ORDER BY last_watched DESC LIMIT 1")
  suspend fun lastWatched(): ChannelEntity?
  ```
- In `LiveViewModel`, after `refreshGuide()` completes, determine the index of the last-watched channel.
- Expose `lastWatchedIndex: StateFlow<Int?>` for `LiveScreen` to use with `TvLazyColumn.scrollToItem()`.

### LiveScreen: Auto-Scroll

```kotlin
val lastWatchedIndex by viewModel.lastWatchedIndex.collectAsState()

LaunchedEffect(lastWatchedIndex) {
    lastWatchedIndex?.let { index ->
        listState.scrollToItem(index)
    }
}
```

### Settings: Auto-Play on Launch

- Add a user preference in `SettingsViewModel` / `SettingsScreen`: "Start on last channel" (default: off).
- If enabled, `Shell.kt` checks for a last-watched channel on launch and opens the player directly via `nav.openPlayer(PlayerArgs(...))`.

### Files to Modify

| File | Change |
|------|--------|
| `data/db/Daos.kt` | Add `lastWatched()` query to `ChannelDao`. |
| `ui/player/PlayerViewModel.kt` | Inject `ChannelDao`, call `markWatched()` on init and channel switch. |
| `ui/live/LiveViewModel.kt` | Query last-watched channel, expose index for scroll-to. |
| `ui/live/LiveScreen.kt` | Auto-scroll `TvLazyColumn` to last-watched index on first load. |
| `di/AppModule.kt` | No change needed -- `ChannelDao` is already provided. |
| `ui/settings/SettingsScreen.kt` | (Optional) Add "Start on last channel" toggle. |
| `ui/nav/Shell.kt` | (Optional) Auto-play last channel on app launch if setting enabled. |

## Priority

**High** -- A fundamental expectation of TV UX. The database column already exists, it just needs to be wired up.

## Effort Estimate

**Small (1-2h)** -- `markWatched()` calls are one-liners. The scroll-to logic is straightforward with `TvLazyColumn`'s `LazyListState`. The optional auto-play-on-launch is a small addition to Shell.kt.
