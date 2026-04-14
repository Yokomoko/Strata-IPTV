# 27 - Search Result Images (Poster Thumbnails)

## Current State

The search results list (`ResultsList` in `SearchScreen.kt`) renders each result as a `ListItem` with:
- A leading icon (`Icons.Outlined.Movie` / `Icons.Outlined.VideoLibrary` / `Icons.Outlined.LiveTv`) -- a small 22dp Material icon, not a content image.
- Headline text (title).
- Supporting text (group title).
- A trailing type badge ("MOVIE", "SHOW", "LIVE").

The `SearchResult` data class contains an `artworkUrl` field (from `ContentItemEntity.artworkUrl`), and the underlying `MovieEntity` has a `posterUrl` field populated by TMDB enrichment. However, **neither artwork is displayed in search results**. The search results are purely text-based with generic type icons.

## Gap

Netflix, Disney+, and Prime Video all show poster thumbnails in their search results. Visual recognition is dramatically faster than reading text -- users can identify a movie by its poster in ~200ms versus ~800ms for reading a title. The text-only search results make Strata feel like a file browser rather than a streaming app. This is especially jarring because poster images are already loaded and cached for the Home rails.

## User Story

As a Strata user searching for "Batman", I want to see poster thumbnails next to each search result so that I can quickly visually identify the specific movie or show I am looking for.

## Acceptance Criteria

1. Each movie search result displays a small poster thumbnail (approximately 48dp wide x 72dp tall) in the leading content area, replacing the generic movie icon.
2. Each show search result displays the series poster thumbnail similarly.
3. Live channel results retain the LiveTv icon (channels rarely have poster-quality artwork).
4. If a movie/show has no poster URL, the result falls back to the existing Material icon.
5. Poster thumbnails load from Coil's memory/disk cache (same images already used on Home rails) with no visible loading delay for cached images.
6. A subtle placeholder shimmer or colored background is shown while the image loads for uncached posters.
7. Search result row height increases gracefully to accommodate the thumbnail without breaking the layout or D-pad focus behavior.

## Technical Approach

1. **ResultRow refactor**: In the `ResultRow` composable, conditionally replace the `Icon` leading content with an `AsyncImage` when `result.artworkUrl` is not blank (for movies and shows):

   ```kotlin
   leadingContent = {
       val imageUrl = result.artworkUrl.takeIf {
           it.isNotBlank() && result.contentType != "live"
       }
       if (imageUrl != null) {
           AsyncImage(
               model = imageUrl,
               contentDescription = result.title,
               contentScale = ContentScale.Crop,
               modifier = Modifier
                   .width(48.dp)
                   .height(72.dp)
                   .clip(RoundedCornerShape(6.dp)),
           )
       } else {
           Icon(imageVector = icon, ...)
       }
   }
   ```

2. **Poster URL enrichment for search**: The `SearchResult` currently maps `artworkUrl` from `ContentItemEntity.artworkUrl`. For movies, this may be empty even when `MovieEntity.posterUrl` is populated (they are separate tables). Enhance `SearchViewModel.executeSearch()` to cross-reference movie results with `MovieDao` to get the poster URL, or add a JOIN in the search query:
   ```sql
   SELECT c.*, COALESCE(m.poster_url, c.artwork_url) AS enriched_poster
   FROM content_items c LEFT JOIN movies m ON c.content_id = m.content_id
   WHERE ...
   ```

3. **Row height**: Increase the `ListItem` height from the default to at least 80dp to accommodate the poster thumbnail. Adjust padding to maintain visual balance.

4. **Image sizing**: Use Coil's `size()` modifier to request a thumbnail-sized image, reducing memory usage compared to loading the full poster resolution.

5. **Performance**: Since search results are capped at 30 movies + 30 shows + 5 channels = 65 items max, loading 60 poster thumbnails is well within Fire Stick memory budget. Coil's disk cache means most posters are already available locally.

## Priority

**P1 - High**. This is a small change with outsized visual impact. Search results currently look utilitarian; thumbnails make them look professional. The artwork data already exists.

## Effort Estimate

**Small (1 day)**. Primarily a UI change to `ResultRow` with a minor DAO or ViewModel tweak to ensure poster URLs are available in search results.
