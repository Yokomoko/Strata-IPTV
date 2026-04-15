# 59 - Content Removed Without Warning

## Title

Shows and films disappear from the service without any notice

## Source

- **Reddit**: r/netflix, r/cordcutters, r/DisneyPlus -- "I was halfway through a series and it just vanished." "Netflix removed my favourite show with no warning."
- **Tech press**: Regular articles listing content leaving Netflix/Disney+ each month. Third-party sites like "What's on Netflix" exist solely because the apps themselves don't communicate departures.
- **Amazon Appstore reviews**: Users lose watchlist items and continue watching progress when content is removed.
- **r/IPTV**: IPTV playlists change frequently as providers add/remove channels. Users open the app to find favourite channels gone with no explanation.

## The Problem

Content removal from streaming services is abrupt and opaque:

1. **No advance warning** -- titles disappear overnight. Users halfway through a series find it gone.
2. **No notification** -- apps don't notify users that content on their watchlist or in Continue Watching is scheduled for removal.
3. **Watchlist entries just vanish** -- saved titles silently disappear from My List when the content is removed.
4. **Continue Watching broken** -- a partially watched film is removed, and the Continue Watching entry either lingers (pointing to nothing) or vanishes with no explanation.
5. **IPTV-specific**: Channels disappear between playlist updates. The favourite channel the user set up yesterday is gone today.

## How StrataTV Could Address It

1. **Channel/content change detection** -- on each playlist refresh (spec 33), compare the new playlist against the previous one. Detect removed channels, added channels, and URL changes.
2. **"Removed channels" notification** -- on app launch after a playlist refresh, show a subtle notification: "2 channels were removed from your playlist since last time. 5 new channels were added."
3. **Favourite preservation** -- if a favourited channel is removed from the playlist, keep the favourite entry in a "Recently Removed" section rather than silently deleting it. If the channel reappears in a future update, automatically restore it to favourites.
4. **Continue Watching cleanup** -- if VOD content referenced in Continue Watching no longer exists in the playlist, show a "This content is no longer available" message rather than a cryptic error.
5. **Changelog view** -- an optional "Playlist Changes" screen showing recent additions and removals with dates.

## Feasibility Score

**2** (low effort) -- Playlist diff logic on the existing M3U parser output. Store previous channel/content IDs in Room. Compare sets on refresh.

## Validity Score

**4** (very common) -- Affects all streaming users (content licensing changes) and all IPTV users (playlist provider changes). IPTV playlists change more frequently than streaming service catalogues, making this MORE relevant for StrataTV than for Netflix.

## Impact Score

**8** (Feasibility 2 x Validity 4 = 8)

## Technical Notes

- **Playlist diff engine**: After M3U parse, compare incoming channel IDs against stored channel IDs:
  ```kotlin
  data class PlaylistDiff(
      val added: List<ChannelEntity>,
      val removed: List<ChannelEntity>,
      val urlChanged: List<Pair<ChannelEntity, ChannelEntity>>, // old, new
  )
  
  fun computeDiff(
      existing: List<ChannelEntity>,
      incoming: List<ChannelEntity>
  ): PlaylistDiff {
      val existingIds = existing.map { it.channelId }.toSet()
      val incomingIds = incoming.map { it.channelId }.toSet()
      return PlaylistDiff(
          added = incoming.filter { it.channelId !in existingIds },
          removed = existing.filter { it.channelId !in incomingIds },
          urlChanged = // match by name, compare URLs
      )
  }
  ```
- **Change notification**: Store the diff in a `playlist_changes` table:
  ```kotlin
  @Entity(tableName = "playlist_changes")
  data class PlaylistChangeEntity(
      @PrimaryKey(autoGenerate = true) val id: Long = 0,
      val changeType: String,     // "added", "removed", "url_changed"
      val channelName: String,
      val detectedAt: Long,
      val acknowledged: Boolean = false
  )
  ```
- **Home screen notification**: On launch, query unacknowledged changes. If count > 0, show a small card at the top of the home screen: "Your playlist was updated: +5 channels, -2 channels. Tap to see details."
- **Favourite resilience**: When a favourite channel is removed from the playlist, set `FavouriteEntity.isAvailable = false` instead of deleting. Show unavailable favourites greyed out with a "Currently unavailable" label. On next refresh, if the channel reappears, set `isAvailable = true`.
- **Continue Watching orphan handling**: Before rendering the Continue Watching rail, validate that each entry's `contentId` still exists in the content tables. Replace missing entries with a "No longer available" card, or remove them silently after showing the notification.
- **Fire Stick constraint**: Playlist diff runs during background sync (spec 33), not on the UI thread. The diff computation is O(n) set operations on channel ID lists, negligible even for 10,000+ channel playlists.

## Priority Recommendation

**P2 -- Implement alongside background sync (spec 33).** The playlist diff is a natural extension of the sync mechanism. Adding it after sync is built is straightforward. The favourite resilience and change notification create a significantly more trustworthy experience than competitors, where content simply vanishes.
