# 23 - Genre Browse Screen

## Current State

Genres exist on the Home screen as horizontal rails (top 8 genres by frequency, max 20 movies each, ordered by TMDB rating). The search screen offers quick-search genre chips ("Action", "Comedy", etc.) that populate the search field and run a text search. However, there is no dedicated genre browse screen. Tapping a genre chip in search just runs a text query -- it does not open a filtered, sortable grid of all content in that genre. The `MovieDao.byGenre()` method exists but caps at 40 results and returns `MovieListItem` with no sorting options. There is no way to browse all 200+ action movies; the user sees at most 20 in the Home rail.

## Gap

Netflix, Disney+, and Prime Video all provide dedicated genre landing pages with the full catalogue filtered by genre, sorting options (popular, recently added, A-Z), and sub-genre filtering. Strata's genre rails are a teaser, not a browse experience. Users who want to explore a genre exhaustively have no path to do so.

## User Story

As a Strata user who is in the mood for horror movies, I want to open a dedicated Horror genre screen showing all horror titles with sorting options so that I can browse the full catalogue, not just the 20 shown on the Home rail.

## Acceptance Criteria

1. A new "Genre Browse" screen is accessible from: (a) tapping a genre chip on the search screen, (b) a "See All" affordance on Home genre rails, (c) a top-level "Genres" option in the sidebar or a sub-menu within Movies.
2. The screen displays a poster grid of all visible movies matching the selected genre.
3. Sort options are available: "Popular" (rating desc), "Recently Added" (year desc), "A-Z" (title asc), "Year" (year desc).
4. The grid supports both movies and series (tabs or a toggle to switch content type).
5. The screen loads the first page (40 items) within 300ms on Fire Stick; additional items load as the user scrolls (lazy pagination).
6. Focus management: D-pad navigation moves naturally through the grid; pressing Back returns to the previous screen.
7. Each poster card shows the same PosterCard treatment as the Home rails.

## Technical Approach

1. **Navigation**: Add `DetailRoute.GenreBrowse(genre: String)` to `AppNavState`. Alternatively, add a `Destination.Genres` sidebar entry that opens a genre picker, then navigates to the browse grid.

2. **ViewModel**: Create `GenreBrowseViewModel` that accepts a genre parameter and exposes:
   - `movies: StateFlow<List<MovieListItem>>` from `MovieDao.byGenre()` with an increased limit (200+).
   - `sortOrder: StateFlow<SortOrder>` (enum: Popular, RecentlyAdded, AtoZ, Year).
   - `contentType: StateFlow<ContentTypeFilter>` (All, Movies, Shows).
   - Sorting applied in-memory after the initial query to avoid multiple DAO methods.

3. **DAO changes**: Add `MovieDao.byGenrePaged(genre: String, limit: Int, offset: Int): List<MovieListItem>` for pagination. Add equivalent `SeriesDao.byGenre()`.

4. **UI**: Create `GenreBrowseScreen.kt` with:
   - Header showing the genre name and a sort dropdown (TvLazyRow of chips).
   - Body: `TvLazyVerticalGrid` (or manual row-based layout for TV) of `PosterCard` items.
   - "See All" button on Home genre rail headers that calls `onNavigate.openGenreBrowse(genre)`.

5. **RAM budget**: Use `MovieListItem` (lightweight projection) and lazy grid with a max of 40 items visible. Coil image caching handles poster memory.

## Priority

**P2 - Medium**. Genre browse is a significant discovery enhancement but the Home genre rails provide a partial solution. This is most valuable once the library grows beyond a few hundred titles.

## Effort Estimate

**Medium-Large (4-5 days)**. New screen, new ViewModel, DAO pagination, navigation integration, sort/filter UI, and focus management for the grid.
