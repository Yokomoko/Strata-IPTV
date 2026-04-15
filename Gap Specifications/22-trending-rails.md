# 22 - Trending / Popular Rails

## Current State

The Home screen's "Recently Added" rail is ordered by `year DESC`, which surfaces newer releases but does not reflect what is actually popular or trending. The genre rails are ordered by TMDB `rating DESC`, which biases toward critically acclaimed but potentially obscure titles. There is no "Trending Now" or "Popular on Strata" rail. The TMDB API already fetches `vote_average` during enrichment, but the `popularity` field from TMDB search results is not persisted. The local `watch_history` table is never aggregated to derive popularity signals.

## Gap

Netflix, Disney+, and Prime Video all feature "Trending Now", "Top 10", and "Popular" rails prominently on the Home screen. These rails create a sense of currency and social proof. Strata has two data sources that could power this -- TMDB's global popularity metric and local watch_history aggregation -- but uses neither.

## User Story

As a Strata user, I want to see a "Trending Now" rail on the Home screen so that I can quickly find popular content that other viewers are watching, without scrolling through genre rails.

## Acceptance Criteria

1. A "Trending Now" rail appears as the first content rail on the Home screen (after Continue Watching and Watchlist, before Recently Added).
2. The rail displays up to 20 titles ordered by a blended popularity score combining TMDB vote count/average and local watch frequency.
3. A "Top 10" badge overlay appears on the first 10 items in the Trending rail with a numbered rank indicator.
4. The trending data refreshes at most once per 24 hours (aligned with playlist sync) to avoid excessive computation.
5. Content that the user has fully watched still appears in Trending (unlike recommendation rails) since trending reflects global popularity, not personal relevance.
6. The rail is visually differentiated from standard genre rails (distinct accent color or section header styling).

## Technical Approach

1. **Schema change**: Add a `popularity` REAL column (default 0.0) to the `movies` table. Populate during enrichment from `TmdbMovie.voteAverage * log(voteCount)` or use the TMDB `/trending/movie/week` endpoint (free, no extra API key needed).

2. **TMDB trending endpoint**: Add a `trendingMovies()` call to `TmdbApi` hitting `GET /trending/movie/week`. Cross-reference returned TMDB IDs against the local `movies` table (which already stores `tmdb_id`). Only surface titles that exist in the user's library.

3. **Local popularity signal**: Query `watch_history` grouped by `content_id` with `COUNT(*)` and `SUM(duration_watched_ms)` over the last 7 days. Blend with TMDB popularity: `score = (tmdb_popularity * 0.6) + (local_watch_count * 0.4)`.

4. **DAO addition**: Add `MovieDao.byTmdbIds(ids: List<Int>): List<MovieListItem>` and `MovieDao.topByPopularity(limit: Int): List<MovieListItem>`.

5. **ViewModel**: Add `trendingRail: StateFlow<List<MovieListItem>>` to `HomeViewModel`, built once during init alongside genre rails.

6. **Top 10 badge**: Create a `RankedPosterCard` composable that wraps `PosterCard` and overlays a large bold number (Netflix-style) in the bottom-left corner for ranks 1-10.

## Priority

**P1 - High**. Trending rails are table stakes for streaming app feel. The TMDB trending endpoint is free and the local watch_history data already exists.

## Effort Estimate

**Medium (3-4 days)**. One new API endpoint, a popularity column migration, blending logic, and a new card overlay composable.
