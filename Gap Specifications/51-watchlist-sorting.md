# 51 - No Way to Sort or Organise the Watchlist

## Title

Watchlist/My List has no sort options and becomes an unsearchable mess

## Source

- **Reddit**: r/netflix, r/DisneyPlus, r/cordcutters -- "My Netflix list has 200 titles and I can't sort it alphabetically." "Disney+ My Watchlist is just a pile."
- **Amazon Appstore reviews**: Watchlist organisation complaints appear for every streaming app.
- **UX teardowns**: Multiple articles criticise the "dump everything in one list" approach of streaming watchlists.
- **r/PleX**: Plex users praise its library sorting as a key advantage over streaming services.

## The Problem

Watchlists on streaming apps become unusable as they grow:

1. **No sort options** -- Netflix's "My List" sorts by date added with no option to change. No alphabetical, no genre grouping, no custom order.
2. **No search within watchlist** -- with 100+ saved titles, finding a specific one requires scrolling through the entire list.
3. **No folders or categories** -- everything is in one flat list. Users cannot group titles (e.g., "Friday night films", "Watch with kids", "Documentary backlog").
4. **Stale entries** -- titles saved months ago stay in the list indefinitely. No prompts to review or clean up.
5. **No bulk operations** -- removing multiple titles requires individually selecting and deleting each one.
6. **Cross-platform sync issues** -- watchlist additions on mobile don't always appear on TV, and vice versa.

## How StrataTV Could Address It

1. **Multiple sort modes** -- Sort by: Date Added (newest/oldest), Alphabetical (A-Z/Z-A), Genre, Recently Watched, Duration.
2. **User-created lists** -- beyond a single "Watchlist", allow creating custom lists: "Movie Night", "Kids Shows", "Background TV".
3. **Search within list** -- a filter bar at the top of the watchlist that filters as the user types (or voice search on Fire TV remote).
4. **Bulk operations** -- "Edit mode" where the user can select multiple titles and move/remove them in batch.
5. **Smart lists** -- auto-generated lists like "Added more than 3 months ago" or "Available this week" (for live TV scheduling).

## Feasibility Score

**2** (low effort) -- Room queries with different `ORDER BY` clauses, a few extra tables for custom lists, and a sort-mode dropdown UI. Straightforward database and compose work.

## Validity Score

**4** (very common) -- Anyone with a substantial watchlist experiences this. More acute for power users, but even casual users with 20+ saved titles want alphabetical sorting.

## Impact Score

**8** (Feasibility 2 x Validity 4 = 8)

## Technical Notes

- **Database schema**: 
  ```kotlin
  @Entity(tableName = "user_lists")
  data class UserListEntity(
      @PrimaryKey(autoGenerate = true) val listId: Long = 0,
      val name: String,
      val icon: String?,          // emoji or icon name
      val createdAt: Long,
      val sortMode: String = "date_added_desc"
  )
  
  @Entity(
      tableName = "user_list_items",
      foreignKeys = [ForeignKey(entity = UserListEntity::class, ...)]
  )
  data class UserListItemEntity(
      @PrimaryKey(autoGenerate = true) val id: Long = 0,
      val listId: Long,
      val contentId: String,
      val contentType: String,   // "channel", "vod", "series"
      val title: String,
      val addedAt: Long,
      val customPosition: Int?   // for manual ordering
  )
  ```
- **Sort modes**: Implemented as DAO query variants:
  ```kotlin
  @Query("SELECT * FROM user_list_items WHERE listId = :id ORDER BY title ASC")
  fun getByTitleAsc(id: Long): Flow<List<UserListItemEntity>>
  
  @Query("SELECT * FROM user_list_items WHERE listId = :id ORDER BY addedAt DESC")
  fun getByDateDesc(id: Long): Flow<List<UserListItemEntity>>
  ```
- **Sort dropdown UI**: A `DropdownMenu` anchored to a sort icon in the top bar. D-pad navigable with `TvLazyColumn` items.
- **Default list**: Create a "Favourites" list automatically on first launch. This is the default quick-add target (long-press on a content card > "Add to Favourites").
- **Bulk edit mode**: Toggle via a top-bar "Edit" button. In edit mode, D-pad Select toggles item checkboxes. A bottom action bar shows "Move to...", "Remove (N)".
- **Fire Stick constraint**: Lists should be paginated or lazy-loaded if they exceed 100 items. Use `TvLazyVerticalGrid` for a watchlist grid view.

## Priority Recommendation

**P2 -- Build after core Favourites feature (spec 03).** Spec 03 covers basic favourite channels. This spec extends the concept to a full user list system with sorting and custom lists. The sort dropdown should be included from the start of the Favourites screen; custom lists can be added incrementally. High user satisfaction for relatively low effort.
