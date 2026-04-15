# 30 - Recently Watched History Screen

## Current State

Strata has two related but distinct data structures for watch tracking:

1. **Continue Watching** (`continue_watching` table): Shows in-progress content on the Home screen as a horizontal rail. Limited to the most recent 20 items. Items are removed when the user finishes watching or manually removes them. This is a "resume" feature, not a history feature.

2. **Watch History** (`watch_history` table): An append-only log recording every play session with `content_id`, `content_type`, `duration_watched_ms`, and `watched_at`. `WatchHistoryDao.watchRecent(limit = 50)` exists but is **never consumed by any UI component**. This table grows indefinitely but is invisible to the user.

There is no dedicated screen showing full watch history. The Continue Watching rail on the Home screen only shows resumable items, not completed watches. Once a movie is fully watched and cleared from Continue Watching, there is no way to find it again without searching.

## Gap

Netflix has "Viewing Activity" in account settings. Prime Video has "Watch History" as a dedicated page. Disney+ shows "Continue Watching" and recently completed titles. Strata's Continue Watching rail is a limited proxy -- it only shows in-progress items and caps at 20. Users who want to re-watch something from weeks ago, or just review what they have watched, have no way to do so.

## User Story

As a Strata user, I want to see a complete history of everything I have watched, including titles I finished, so that I can re-watch favorites or remember what I saw last week.

## Acceptance Criteria

1. A "History" screen is accessible from the sidebar navigation (new `Destination.History` entry) or from a link in the Settings screen.
2. The screen displays all entries from `watch_history` in reverse chronological order (most recent first).
3. Each entry shows: title, poster thumbnail, content type badge, date watched, and duration watched.
4. Movie entries are clickable and navigate to the movie detail screen.
5. Show entries are clickable and navigate to the show detail screen.
6. The list supports pagination / lazy loading for users with extensive history.
7. A "Clear History" option is available (with confirmation dialog) to delete all watch history entries.
8. Individual entries can be removed via long-press context menu (KEYCODE_MENU).
9. The screen groups entries by date (e.g., "Today", "Yesterday", "This Week", "Earlier").

## Technical Approach

1. **Navigation**: Add `Destination.History` to the sidebar enum, with an appropriate icon (`Icons.Outlined.History`).

2. **HistoryViewModel**: Create a ViewModel that:
   - Observes `WatchHistoryDao.watchRecent(limit = 200)`.
   - Enriches each `WatchHistoryEntity` with display data by joining with `MovieDao`/`SeriesDao`/`ContentDao` to get title, poster URL, and content type label.
   - Groups entries by date bucket (Today, Yesterday, This Week, This Month, Earlier) using `watched_at` timestamps.
   - Exposes `val history: StateFlow<List<HistoryGroup>>`.

3. **Enriched history model**:
   ```kotlin
   data class HistoryEntry(
       val contentId: String,
       val title: String,
       val posterUrl: String,
       val contentType: String,
       val durationWatched: String,  // formatted "1h 23m"
       val watchedAt: Instant,
   )
   data class HistoryGroup(
       val label: String,  // "Today", "Yesterday", etc.
       val entries: List<HistoryEntry>,
   )
   ```

4. **DAO additions**:
   - `WatchHistoryDao.deleteAll()` for clear history.
   - `WatchHistoryDao.delete(id: Int)` for individual removal.

5. **UI**: Create `HistoryScreen.kt` with a `TvLazyColumn` rendering date group headers and `ListItem` rows (similar to search results but with poster thumbnails and date/duration metadata).

6. **Sidebar placement**: Insert between Search and Settings in the `Destination` enum.

## Priority

**P2 - Medium**. The watch_history data is already being collected but is completely invisible to the user. Surfacing it requires moderate effort but provides real utility for re-watching and review.

## Effort Estimate

**Medium (3-4 days)**. New screen, new ViewModel, DAO additions, sidebar navigation integration, date grouping logic, and history entry enrichment.
