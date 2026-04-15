# 25 - Related Content on Detail Screen ("More Like This")

## Current State

The movie detail screen (`MovieDetailScreen.kt`) shows a single movie's metadata, poster, backdrop, overview, cast, action buttons (Play, Trailer, Watchlist), and nothing else. After the action buttons, the screen content ends. There is no "More Like This", "Similar Titles", or "You Might Also Like" rail. The show detail screen (`ShowDetailScreen.kt`) similarly ends after the episode list with no related content suggestions. Once a user finishes reading about a movie, their only options are to play it, add it to the watchlist, or press Back -- there is no path to discover related content from the detail page.

## Gap

Every major streaming app places a "More Like This" rail on the detail screen. Netflix shows 3 rows of related titles. Disney+ shows "Recommended" and "Extras". Prime Video shows "Customers Also Watched". This rail is a critical engagement loop -- it keeps users browsing rather than navigating back to the Home screen. Strata's detail screens are dead ends for discovery.

## User Story

As a Strata user viewing the detail page for a sci-fi movie, I want to see a "More Like This" rail of similar sci-fi movies below the action buttons so that I can continue browsing related content without going back to the Home screen.

## Acceptance Criteria

1. A "More Like This" horizontal rail appears below the action buttons on `MovieDetailScreen`.
2. A "More Like This" horizontal rail appears below the episode list on `ShowDetailScreen`.
3. The rail contains 10-20 titles that share genre overlap with the current title, ordered by rating descending.
4. Clicking a title in the "More Like This" rail navigates to that title's detail screen (in-place replacement, with Back returning to the previous detail).
5. The current title is excluded from its own "More Like This" results.
6. Titles already in the user's watchlist are not excluded (unlike recommendation rails) since this is a browsing context.
7. The rail uses the standard `PosterCard` and `Rail` composables for visual consistency.
8. On Fire Stick, the rail loads within 200ms and does not cause the detail screen scroll to stutter.

## Technical Approach

1. **MovieDetailViewModel**: Add a `relatedMovies: StateFlow<List<MovieListItem>>` flow. When `load(contentId)` resolves the movie, extract its primary genre(s), then query `MovieDao.byGenre(genre, limit = 30)`, filter out the current movie, deduplicate by title, and take 20.

2. **ShowDetailViewModel**: Add a `relatedShows: StateFlow<List<SeriesEntity>>` flow. Query `SeriesDao` for series matching the current show's primary genre. (A new `SeriesDao.byGenre()` query will be needed.)

3. **Scoring refinement**: For movies, enhance matching beyond genre: prefer titles with overlapping cast members (simple string intersection on the comma-separated `cast` field) and matching provider. Score = `genreOverlap * 2 + castOverlap + providerMatch`.

4. **UI - MovieDetailScreen**: After the action buttons row and the existing `Spacer`, add:
   ```kotlin
   if (relatedMovies.isNotEmpty()) {
       Rail(
           title = "More Like This",
           accentColor = StrataColors.AccentSecondary,
           items = relatedMovies,
       ) { _, movie -> PosterCard(...) { onNavigate.openMovieDetail(movie.contentId) } }
   }
   ```

5. **UI - ShowDetailScreen**: Same pattern below the episode list.

6. **Navigation stack**: Since `openMovieDetail()` replaces `detailRoute`, navigating from one detail screen to another via "More Like This" will work, but Back will return to the original content screen (not the intermediate detail). Consider maintaining a detail back stack if deeper navigation is desired.

7. **DAO addition**: Add `SeriesDao.byGenre(genre: String, limit: Int): List<SeriesEntity>`.

## Priority

**P1 - High**. "More Like This" is table stakes for any streaming detail page. It directly increases time-in-app and content discovery. The implementation is straightforward since genre data and the Rail composable already exist.

## Effort Estimate

**Small-Medium (2-3 days)**. Primarily ViewModel additions and a Rail composable insertion on each detail screen. The DAO queries are minor extensions of existing patterns.
