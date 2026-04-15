# 57 - Continue Watching Showing Finished or Stale Content

## Title

Continue Watching rail filled with shows I finished or abandoned months ago

## Source

- **Reddit**: r/netflix, r/DisneyPlus, r/PleX -- "My Continue Watching has 30 items and half of them I finished." "I watched the last 5 minutes of a film and now it won't leave Continue Watching." "How do I remove something from Continue Watching on Disney+?"
- **Amazon Appstore reviews**: A persistent complaint across all streaming apps on Fire Stick.
- **r/IPTV**: IPTV apps that do implement continue watching often have no way to clear entries.
- **UX teardowns**: The Continue Watching rail's value degrades rapidly if it becomes cluttered with stale entries.

## The Problem

The "Continue Watching" rail on streaming apps becomes useless over time:

1. **Finished content not removed** -- watching to 95-99% of a title leaves it in Continue Watching permanently. Netflix is particularly bad at detecting completion.
2. **No manual removal** -- Disney+ and Apple TV+ historically had no way to remove individual entries from Continue Watching on the TV app.
3. **Abandoned content lingers** -- a title started and abandoned 6 months ago still appears, pushing recent content further right.
4. **Reverse-chronological disorder** -- some apps don't sort by last watched, so the most relevant entries are buried.
5. **Too many entries** -- power users accumulate dozens of Continue Watching entries with no pagination or limit.
6. **Children's content mixing** -- on shared devices, kids' shows appear in the adult Continue Watching rail.

## How StrataTV Could Address It

1. **Smart completion detection** -- mark content as finished when position reaches 90% of duration (tuned for content that has credits). Auto-remove from Continue Watching.
2. **Time-based expiry** -- entries older than 30 days without activity are auto-archived (still accessible via watch history, but removed from the home screen rail).
3. **Manual removal** -- long-press on any Continue Watching card to reveal "Remove from Continue Watching" option.
4. **Limited rail size** -- show a maximum of 10-15 items in the Continue Watching rail. Older entries are accessible via a "See All" screen.
5. **Sorted by recency** -- always sorted by last watched timestamp, most recent first.
6. **"Start Over" option** -- for content that was started accidentally or that the user wants to re-watch from the beginning, offer "Start Over" which resets the position.
7. **Clear all** -- a "Clear Continue Watching" option in settings for a fresh start.

## Feasibility Score

**2** (low effort) -- Room queries with time filters, a completion threshold constant, and a long-press context menu. The data model for continue watching should already include timestamps.

## Validity Score

**4** (very common) -- Affects every user of every streaming app. The Continue Watching rail is prime screen real estate on the home screen, and clutter directly degrades the launch experience.

## Impact Score

**8** (Feasibility 2 x Validity 4 = 8)

## Technical Notes

- **Completion threshold**:
  ```kotlin
  companion object {
      const val COMPLETION_THRESHOLD = 0.90f  // 90% watched = finished
      const val EXPIRY_DAYS = 30L
      const val MAX_CONTINUE_WATCHING_ITEMS = 15
  }
  ```
- **ContinueWatchingEntity refinements**:
  ```kotlin
  @Entity(tableName = "continue_watching")
  data class ContinueWatchingEntity(
      @PrimaryKey val contentId: String,
      val title: String,
      val contentType: String,     // "movie", "episode", "live"
      val positionMs: Long,
      val durationMs: Long,
      val updatedAt: Long,         // last watch timestamp
      val finished: Boolean,       // auto-set when position/duration > 0.90
      val artworkUrl: String?,
      val seriesTitle: String?,    // for grouping episodes
      val seasonNumber: Int?,
      val episodeNumber: Int?
  )
  ```
- **Home screen rail query**:
  ```sql
  SELECT * FROM continue_watching
  WHERE finished = 0
  AND updatedAt > :thirtyDaysAgo
  ORDER BY updatedAt DESC
  LIMIT 15
  ```
- **Manual removal**: Long-press handler on the Continue Watching card composable:
  ```kotlin
  Modifier.onLongClick {
      viewModel.removeFromContinueWatching(contentId)
  }
  ```
  Or via a context menu: long-press opens a menu with "Remove" and "Start Over" options.
- **Auto-cleanup**: A coroutine in `HomeViewModel.init {}` that runs:
  ```kotlin
  continueWatchingDao.deleteExpired(System.currentTimeMillis() - 30.days.inWholeMilliseconds)
  continueWatchingDao.deleteFinished() // finished items older than 24 hours
  ```
- **Watch history preservation**: Removing from Continue Watching does NOT delete watch history. A separate `watch_history` table preserves all viewing records for statistics and recommendations.
- **Fire Stick constraint**: The Continue Watching rail is the first rail on the home screen and must render instantly. The 15-item limit ensures the `TvLazyRow` has minimal items to compose.

## Priority Recommendation

**P1 -- Build into the Continue Watching system from the start.** The completion detection, expiry, and manual removal should be part of the initial Continue Watching implementation, not bolted on later. A well-maintained Continue Watching rail is one of the highest-value UI elements on the home screen. Getting it right from day one creates a significantly better first impression than competitors.
