# 65 - No Global Watchlist Across Content Sources

## Title

Watchlist is fragmented across apps -- no single view of everything I want to watch

## Source

- **Reddit**: r/cordcutters (top complaint category), r/fireTV, r/AndroidTV -- "I have separate watchlists on Netflix, Disney+, Prime, and Plex. I just want one list." "I forget which app has the show I saved."
- **Tech press**: Numerous articles about the "streaming fragmentation problem" and "too many apps" fatigue.
- **r/IPTV**: IPTV users with multiple playlists face the same fragmentation within a single app category.
- **Apple TV app**: Apple's TV app attempted to solve this with a unified watchlist, receiving praise for the concept but criticism for incomplete integration.

## The Problem

Every streaming service has its own isolated watchlist:

1. **Fragmented save states** -- a user's "want to watch" list is split across 5+ apps with no unified view.
2. **Lost context** -- "I saved a show somewhere but can't remember which app." Users resort to third-party apps (JustWatch, TV Time) to maintain a unified list.
3. **Duplicate navigation** -- checking what to watch requires opening 3-4 apps and scrolling through each watchlist.
4. **No cross-app "Continue Watching"** -- finished a show on Netflix, the next season is on Disney+. No app connects this journey.

## How StrataTV Could Address It

StrataTV has a unique advantage: it aggregates multiple IPTV playlists and VOD sources into a single app. This naturally creates a unified content view:

1. **Single watchlist across all playlists** -- content from all configured M3U playlists appears in one unified Favourites/Watchlist. The user doesn't think about "which playlist" -- just "what I want to watch".
2. **Continue Watching across sources** -- the Continue Watching rail shows content from all playlists, sorted by recency.
3. **Unified search** -- search queries span all configured playlists simultaneously.
4. **Source indicator** -- each content card subtly indicates its source playlist (via a small badge or the card's category label), so the user knows where it comes from without needing to think about it.
5. **Multi-playlist support** (spec 32) -- the existing spec for multiple playlists provides the infrastructure. This spec adds the unified watchlist UX on top.

## Feasibility Score

**2** (low effort) -- Since all playlist content is imported into Room, the unified view is simply a database query across all content. The favourites/watchlist table already stores content IDs regardless of source playlist.

## Validity Score

**4** (very common) -- Streaming fragmentation is one of the defining frustrations of modern TV. Every cord-cutter experiences it. IPTV users with multiple provider playlists face an analogous problem within the IPTV world.

## Impact Score

**8** (Feasibility 2 x Validity 4 = 8)

## Technical Notes

- **Database design**: Content from all playlists is stored in a single `channels` / `vod_content` table with a `playlistId` foreign key:
  ```kotlin
  @Entity(tableName = "channels")
  data class ChannelEntity(
      @PrimaryKey val channelId: String,  // unique across playlists
      val playlistId: Long,               // which playlist this came from
      val name: String,
      val streamUrl: String,
      // ...
  )
  ```
  Favourites and Continue Watching reference `channelId` without needing to know the playlist.
- **Unified Favourites query**:
  ```sql
  SELECT c.*, f.addedAt FROM channels c
  INNER JOIN favourites f ON c.channelId = f.channelId
  ORDER BY f.addedAt DESC
  ```
  This naturally spans all playlists.
- **Source badge UI**: A small chip or text label on the content card showing the playlist name:
  ```kotlin
  Text(
      text = playlistName,
      style = MaterialTheme.typography.labelSmall,
      modifier = Modifier.alpha(0.7f)
  )
  ```
- **Cross-reference**: This spec builds on spec `32-multiple-playlists.md` which handles the multi-playlist import infrastructure.
- **Conflict handling**: If the same channel exists in multiple playlists (common with IPTV), deduplicate by channel name + stream URL hash. Prefer the playlist with the more recent update timestamp.
- **Fire Stick constraint**: Querying across all playlists is a single Room query, not N separate queries. No performance impact.

## Priority Recommendation

**P2 -- Natural outcome of multi-playlist support.** This is not a separate feature to build; it is the natural UX outcome of spec 32 (multiple playlists) combined with unified database design. Ensure the database schema supports cross-playlist queries from the start, and this comes for free. The "unified watchlist" is a strong marketing message: "One app. One list. All your content."
