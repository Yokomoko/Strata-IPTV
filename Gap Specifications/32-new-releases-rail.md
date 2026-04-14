# 32 - "New Releases This Week" Automated Rail

## Current State

The Home screen has a "Recently Added" rail that shows movies ordered by `year DESC` (via `MovieDao.watchRecentWithPosters`). This surfaces movies with the most recent release year, but "recently added" is a misnomer -- it actually means "newest by release year", not "recently added to the library". A movie from 2024 that was in the playlist since day one appears alongside a movie from 2025 that was just synced today. There is no timestamp tracking when a movie was first seen in a playlist sync. The `MovieEntity` has no `first_seen_at` or `added_at` column. The `ContentItemEntity` has `last_updated` but this is overwritten on every sync, not preserved as a first-seen date.

## Gap

Netflix prominently features "New This Week" and "New Releases" rails that highlight content freshly added to the catalogue. Prime Video has "Recently Added" that genuinely tracks when content appeared. These rails create urgency and encourage users to check the app regularly. Strata's "Recently Added" rail is static -- it shows the same year-sorted content every time until the next playlist update. There is no concept of "new to the library" versus "new by release year".

## User Story

As a Strata user, I want to see a "New This Week" rail on the Home screen showing movies and shows that were genuinely added to the library in the last 7 days so that I know what fresh content is available since my last visit.

## Acceptance Criteria

1. A "New This Week" rail appears on the Home screen, positioned after Continue Watching and before Trending/Recently Added.
2. The rail contains movies and shows that were first detected in the library within the last 7 days.
3. Content is ordered by the date it was first seen (newest first).
4. The rail updates automatically after each playlist sync.
5. If no new content was added in the last 7 days, the rail is hidden (not shown with 0 items).
6. A "New" badge or indicator appears on the poster cards in this rail to differentiate them from other rails.
7. The 7-day window is configurable (could be extended to "New This Month" in settings).

## Technical Approach

1. **Schema change**: Add a `first_seen_at` TIMESTAMP column to `MovieEntity` and `SeriesEntity`:
   ```sql
   ALTER TABLE movies ADD COLUMN first_seen_at TEXT DEFAULT NULL;
   ALTER TABLE series ADD COLUMN first_seen_at TEXT DEFAULT NULL;
   ```
   Room migration sets `first_seen_at = NULL` for existing rows (they are treated as "not new").

2. **Sync pipeline update**: In `SyncService`, when upserting movies:
   - Before upsert, check if the `content_id` already exists in the movies table.
   - If it is a new insertion (not an update), set `first_seen_at = Instant.now()`.
   - If it already exists, preserve the existing `first_seen_at` value.
   - This requires changing the upsert logic to a conditional insert/update pattern, or using a Room `@Insert(onConflict = IGNORE)` followed by selective updates.

3. **DAO additions**:
   ```kotlin
   @Query("""
       SELECT id, content_id, movie_title, year, runtime, genre,
              poster_url, resume_position_ms, watched, is_favourite,
              language, rating, provider, tmdb_id, hidden
       FROM movies WHERE hidden = 0 AND first_seen_at > :since
         AND poster_url != ''
       ORDER BY first_seen_at DESC
       LIMIT :limit
   """)
   fun watchNewSince(since: Instant, limit: Int = 30): Flow<List<MovieListItem>>
   ```

4. **HomeViewModel**: Add a `newThisWeek: StateFlow<List<MovieListItem>>` flow observing `MovieDao.watchNewSince(Instant.now().minus(7, ChronoUnit.DAYS))`. Build similarly for series.

5. **UI**: Render the rail in `HomeScreen` using the standard `Rail` composable. Add a small "NEW" badge overlay on `PosterCard` for items in this rail (a colored chip in the top-right corner of the poster).

6. **Badge composable**: A small `Box` with `StrataColors.StatusLive` background, "NEW" text in 9sp bold white, positioned with `Modifier.align(Alignment.TopEnd)`.

## Priority

**P2 - Medium**. This feature creates a reason for users to open the app regularly ("what's new?"). The main complexity is tracking first-seen timestamps in the sync pipeline without breaking existing upsert logic.

## Effort Estimate

**Medium (3-4 days)**. Schema migration, sync pipeline modification (the trickiest part), DAO queries, ViewModel flow, and a small badge composable.
