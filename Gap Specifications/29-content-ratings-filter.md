# 29 - Content Ratings Filter (Parental Controls)

## Current State

Content certification data is already collected during TMDB enrichment and stored in `MovieEntity.certification` and `SeriesEntity.certification`. The movie detail screen displays the certification as a `MetadataChip` (e.g., "PG-13", "R", "15"). However, this data is display-only. There are no parental controls, no content filtering by rating, and no way to hide adult-rated content. All content in the library is visible to all viewers regardless of age rating. There is no settings screen option for content filtering, no PIN protection, and no profile-level rating restrictions.

## Gap

Netflix, Disney+, and Prime Video all offer maturity rating filters with PIN-protected settings. Netflix requires a PIN to view content above the profile's maturity level. Disney+ has a dedicated Kids profile. Prime Video has parental controls in settings. For a Fire Stick app used in a household with children, the absence of any content filtering is a significant gap -- especially since the M3U playlist may contain adult content that should not appear in the general browse experience.

## User Story

As a parent using Strata on a shared Fire Stick, I want to set a maximum content rating (e.g., PG-13) so that mature content is hidden from the Home screen, search results, and genre rails when my children are watching.

## Acceptance Criteria

1. A "Content Rating Filter" option appears in the Settings screen.
2. The user can select a maximum allowed rating from a predefined list: "All", "G / U", "PG", "PG-13 / 12", "R / 15", "NC-17 / 18", "Unrated" (show everything).
3. When a filter is active, movies and shows with a certification above the selected threshold are excluded from: Home rails, genre rails, provider rails, search results, and the Movies/Shows grid screens.
4. A 4-digit PIN is required to change the content rating setting. The PIN is set on first use.
5. Attempting to access a hidden title via deep link (e.g., from Continue Watching where the movie was started before the filter was set) prompts for the PIN.
6. The filter persists across app restarts (stored in DataStore/SharedPreferences).
7. Content with no certification data (empty `certification` field) is treated as "Unrated" and visible at all filter levels.

## Technical Approach

1. **Rating hierarchy**: Define a `ContentRating` enum with ordinal ranking:
   ```kotlin
   enum class ContentRating(val labels: Set<String>) {
       G(setOf("G", "U", "TV-G", "TV-Y")),
       PG(setOf("PG", "TV-PG")),
       PG13(setOf("PG-13", "12", "12A", "TV-14")),
       R(setOf("R", "15", "TV-MA")),
       NC17(setOf("NC-17", "18", "X")),
       UNRATED(setOf("")),
   }
   ```

2. **Preferences**: Add a `ContentFilterPrefs` DataStore storing `maxRating: ContentRating` and `pinHash: String` (SHA-256 of the 4-digit PIN).

3. **Domain filter**: Create a `ContentFilter` singleton (injected via Hilt) that:
   - Exposes `maxRating: StateFlow<ContentRating>` from DataStore.
   - Provides `fun isAllowed(certification: String): Boolean` that maps the certification string to a `ContentRating` ordinal and compares against the max.

4. **DAO-level filtering**: For the most impactful filter points, add a certification column check to the DAO queries:
   - `MovieDao.watchRecentWithPosters`: add `AND (certification IN (:allowed) OR certification = '')`.
   - `MovieDao.byGenre`: same.
   - `ContentDao.buildSearchQuery`: add certification filter clause.
   - Alternatively, filter in the ViewModel layer to avoid modifying every DAO query (simpler but less efficient).

5. **ViewModel-level filtering (simpler approach)**: In `HomeViewModel`, `SearchViewModel`, and `MoviesViewModel`, apply `ContentFilter.isAllowed()` as a post-query filter on the results. This is less efficient but avoids touching every DAO query and works correctly for the Fire Stick scale (< 5000 items).

6. **Settings UI**: Add a "Parental Controls" section to the Settings screen with a PIN entry dialog and a rating picker.

7. **PIN dialog**: A simple 4-field numeric input composable with D-pad support (each field accepts 0-9).

## Priority

**P2 - Medium**. Important for family households but not a blocker for single-user setups. The certification data already exists; this is about building the filter and PIN infrastructure.

## Effort Estimate

**Medium (3-4 days)**. PIN dialog, DataStore preferences, ContentFilter domain class, ViewModel filtering integration, Settings screen UI.
