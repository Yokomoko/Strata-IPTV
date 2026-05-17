# 28 - Watchlist from Search Results

## Current State

The watchlist feature exists and works from two places: (1) the movie/show detail screens via a "Watchlist" toggle button, and (2) the Home screen via a long-press context menu (KEYCODE_MENU) on movie cards. However, search results (`ResultRow` in `SearchScreen.kt`) offer no watchlist interaction. The only action available on a search result is clicking to navigate to the detail screen or start playback (for live channels). To add a search result to the watchlist, the user must: click the result, wait for the detail screen to load, then click the Watchlist button -- a 3-step process that interrupts the search flow.

## Gap

Prime Video and Disney+ allow adding titles to a watchlist directly from search results via a long-press or a "+" icon. This is important for "browsing" search behavior where the user searches for a term like "sci-fi" and wants to quickly bookmark multiple results without leaving search. The current flow forces the user out of search context for every watchlist addition.

## User Story

As a Strata user searching for movies, I want to long-press (or press the menu button on) a search result to add it to my watchlist so that I can bookmark multiple titles without leaving the search screen.

## Acceptance Criteria

1. Pressing KEYCODE_MENU on a focused movie or show search result opens a context menu with "Add to Watchlist" (or "Remove from Watchlist" if already added).
2. The context menu uses the same `CardContextMenu` overlay used on the Home screen.
3. After adding/removing, the context menu dismisses and focus returns to the same search result row.
4. A brief toast or visual feedback confirms the action ("Added to Watchlist").
5. The watchlist status of search results updates in real-time (if the user adds a title, the context menu shows "Remove" if they open it again).
6. Live channel results show "Add to Favourites" instead of "Add to Watchlist" (channels use the favourites system).

## Technical Approach

1. **SearchScreen state**: Add context menu state variables (same pattern as `HomeScreen`):
   ```kotlin
   var contextMenuVisible by remember { mutableStateOf(false) }
   var contextMenuActions by remember { mutableStateOf<List<ContextMenuAction>>(emptyList()) }
   ```

2. **SearchViewModel**: Inject `WatchlistDao` and add:
   - `fun addToWatchlist(result: SearchResult)` -- creates a `WatchlistEntity` from the search result.
   - `fun removeFromWatchlist(contentId: String)` -- delegates to `watchlistDao.remove()`.
   - `val watchlistIds: StateFlow<Set<String>>` -- observe `watchlistDao.watchAll()` mapped to a set of content IDs.

3. **ResultRow wrapper**: Wrap each `ResultRow` in a `Box` with `onPreviewKeyEvent` intercepting `KEYCODE_MENU`, same pattern as `MovieCardWithContextMenu` on the Home screen:
   ```kotlin
   Box(modifier = Modifier.onPreviewKeyEvent { event ->
       if (event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
           event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_MENU) {
           contextMenuActions = buildWatchlistActions(result, watchlistIds)
           contextMenuVisible = true
           true
       } else false
   }) { ResultRow(...) }
   ```

4. **Context menu overlay**: Render `CardContextMenu` at the bottom of `SearchScreen`, outside the `TvLazyColumn`.

5. **Visual feedback**: After the watchlist action completes, show a `Snackbar` or a temporary overlay text ("Added to Watchlist") that auto-dismisses after 2 seconds.

## Priority

**P2 - Medium**. Useful quality-of-life improvement for power users who use search as a browsing tool. The building blocks (context menu, watchlist DAO) already exist; this is about wiring them into the search screen.

## Effort Estimate

**Small (1-2 days)**. Reuses existing `CardContextMenu` composable and `WatchlistDao`. Primary work is ViewModel injection and key event wiring in the search screen.
