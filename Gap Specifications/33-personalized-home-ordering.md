# 33 - Personalized Home Screen Ordering

## Current State

The Home screen rail ordering is hardcoded in `HomeScreen.kt`:

1. Hero carousel (hardcoded: 2025+ movies with backdrops, ordered by rating)
2. Continue Watching (if non-empty)
3. My Watchlist (if non-empty)
4. Recently Added (year DESC, limit 20)
5. Provider rails (top 6 providers by movie count, each ordered by rating DESC)
6. Genre rails (top 8 genres by frequency, each ordered by rating DESC)

This ordering never changes based on user behavior. A user who watches 90% action movies sees the same genre rail ordering as a user who watches 90% documentaries. The genre rails are sorted by global frequency (how many movies in the library have that genre tag), not by the user's affinity. The `watch_history` table records genre preferences implicitly (via the content watched) but this signal is never used to reorder rails.

## Gap

Netflix personalizes not just the content within rails but the order of the rails themselves. A user who watches mostly comedies sees the Comedy rail near the top; a user who watches mostly thrillers sees Thriller first. The hero carousel features titles aligned with the user's taste. Prime Video similarly reorders its rows based on viewing patterns. Strata's static ordering means the most relevant genre rail for a given user might be buried at position 8 while irrelevant genres occupy the top slots.

## User Story

As a Strata user who primarily watches action and sci-fi content, I want the Action and Sci-Fi genre rails to appear near the top of the Home screen so that the content most relevant to me is immediately visible.

## Acceptance Criteria

1. Genre rails on the Home screen are ordered by the user's genre affinity (most-watched genres first), not by global catalogue frequency.
2. Genre affinity is computed from watch history: genres associated with titles the user has watched the most (by count and duration) rank higher.
3. The hero carousel prioritizes movies from the user's top 2-3 preferred genres.
4. Provider rails are similarly reordered by user affinity (providers the user watches most appear first).
5. The personalization updates after each viewing session (not requiring an app restart).
6. New users with no watch history see the default frequency-based ordering (graceful degradation).
7. The personalization is subtle -- the user should not notice a jarring reorder, just that the content feels more relevant over time.

## Technical Approach

1. **Genre affinity scoring**: Create a `UserPreferenceEngine` class in `domain/` that:
   - Reads `watch_history` entries from the last 30 days.
   - For each entry, looks up the associated movie/series genre string.
   - Splits genre strings and tallies a score per genre: `+1` per watch, `+0.5` per 30 minutes watched (from `duration_watched_ms`).
   - Returns a `Map<String, Double>` of genre-to-affinity-score.

2. **HomeViewModel modification**: In `buildGenreRails()`, after computing `topGenres` by catalogue frequency, re-sort using genre affinity:
   ```kotlin
   val affinityScores = userPreferenceEngine.genreAffinity()
   val sortedGenres = topGenres.sortedByDescending { genre ->
       affinityScores[genre] ?: 0.0
   }
   ```
   This replaces the current `sortedByDescending { it.value }` (catalogue frequency) with an affinity-weighted sort. For genres with no affinity data, they fall to the end naturally.

3. **Provider affinity**: Similar approach -- tally provider preferences from watch history (join watch_history content IDs with movies table to get provider), and reorder `providerRails` by affinity.

4. **Hero carousel personalization**: In `watchHeroCandidates`, instead of the hardcoded `ORDER BY rating DESC`, add a preference for the user's top genres. One approach: query candidates from each of the user's top 3 genres and interleave them.

5. **Caching**: Compute affinity scores once per app launch (or once per hour) and cache the `Map<String, Double>` in a `StateFlow`. Avoid recomputing on every rail rebuild.

6. **Persistence**: Optionally persist the affinity map in DataStore to avoid cold-start computation on slow Fire Stick hardware. The computation itself should be fast (aggregate query over watch_history is O(n) where n is typically < 500 entries).

7. **Blended score**: Use a blend of affinity and catalogue frequency to avoid a user who watches one documentary once getting documentaries at the top forever: `finalScore = (affinity * 0.7) + (catalogueFrequency * 0.3)`.

## Priority

**P3 - Low**. Personalized ordering is a polish feature that improves over time as watch history accumulates. It depends on having spec #21 (recommendations) or at least a robust watch history. The static ordering is acceptable for initial releases.

## Effort Estimate

**Medium (3-4 days)**. UserPreferenceEngine class, HomeViewModel sorting integration, hero carousel preference query, and caching. No new UI components needed -- only the ordering of existing rails changes.
