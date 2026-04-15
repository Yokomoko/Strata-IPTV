# 21 - "Because You Watched" Recommendations

## Current State

The Home screen displays content rails that are entirely **static in nature**: Recently Added (ordered by year descending), genre rails (top 8 genres by frequency, movies ordered by TMDB rating), and provider rails ("New on Netflix", etc.). There is no personalization based on what the user has actually watched. The `watch_history` table exists and records every play session with `content_id`, `content_type`, `duration_watched_ms`, and `watched_at`, but this data is never read by the Home screen. The `WatchHistoryDao` exposes `watchRecent()` but it is not consumed by `HomeViewModel`.

## Gap

Premium streaming apps (Netflix, Disney+, Prime Video) prominently feature "Because you watched X" rows that surface content related to titles the user has engaged with. This is the single most important driver of content discovery on those platforms. Strata has the raw watch-history data but does not use it to generate any personalized rails, making the Home screen feel generic regardless of how much the user has watched.

## User Story

As a Strata user who has watched several action movies, I want to see a "Because you watched John Wick" rail on the Home screen so that I can discover similar titles I might enjoy without manually searching.

## Acceptance Criteria

1. After a user finishes watching (or watches > 25% of) a movie or show, a "Because you watched [Title]" rail appears on the Home screen on next visit.
2. The rail contains 10-20 titles that share genre, cast, or thematic similarity with the trigger title.
3. A maximum of 3 "Because you watched" rails appear on the Home screen at any time, based on the 3 most recent distinct watches.
4. Titles already in Continue Watching or already fully watched are excluded from recommendation rails.
5. Rails update when new watch history is recorded (no app restart required).
6. The recommendation logic runs entirely on-device with no additional network calls beyond what TMDB enrichment already provides.
7. Performance: rail generation completes within 500ms on Fire Stick hardware; no visible jank or frame drops.

## Technical Approach

1. **Data layer**: Add a `RecommendationEngine` class in `domain/` that:
   - Reads the 3 most recent distinct `content_id` entries from `watch_history` (filtering out live content).
   - For each trigger title, queries `MovieDao` for movies sharing at least one genre tag (using the existing `genre LIKE` pattern) and orders by rating descending.
   - Applies a scoring heuristic: +2 for shared genre overlap count, +1 for matching provider, +0.5 for matching language, +1 for shared cast members (parsed from the comma-separated `cast` column).
   - Returns a `List<RecommendationRail>` each containing title "Because you watched [Title]" and a list of `MovieListItem`.

2. **ViewModel**: Add a `recommendationRails: StateFlow<List<RecommendationRail>>` to `HomeViewModel` that is built in the background coroutine alongside genre/provider rails. Rebuild on each `watch_history` table change (observe via `WatchHistoryDao.watchRecent()`).

3. **UI**: Insert recommendation rails between the Continue Watching rail and the Recently Added rail in `HomeScreen`. Reuse the existing `Rail` composable and `MovieCardWithContextMenu`.

4. **Exclusion set**: Maintain a `Set<String>` of content IDs from Continue Watching and fully-watched movies to filter out of recommendations.

## Priority

**P1 - High**. Personalized recommendations are the defining feature gap between a simple IPTV player and a premium streaming experience. The data infrastructure (watch_history, genre tags, cast) already exists.

## Effort Estimate

**Medium (3-4 days)**. The recommendation engine is SQL queries plus Kotlin scoring logic. No new API calls, no new UI components needed -- just a new domain class and ViewModel wiring.
