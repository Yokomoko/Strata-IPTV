# 55 - No "New Episodes" Notifications or Indicators

## Title

No way to know when new episodes of shows I watch are available

## Source

- **Reddit**: r/cordcutters, r/DisneyPlus, r/fireTV -- "I missed the new episode of Mandalorian because Disney+ didn't tell me." "Netflix should have a 'new episodes' section."
- **Amazon Appstore reviews**: Users want proactive notifications about new content they care about.
- **r/PleX, r/jellyfin**: "New episode" detection and notification is one of the most requested features in self-hosted media servers.
- **r/IPTV**: IPTV users have zero awareness of EPG schedule changes or new VOD additions without manually checking.

## The Problem

Streaming apps do a poor job of notifying users about new content:

1. **No push notifications for new episodes** -- most TV apps don't send push notifications when a new episode of a series you're watching drops.
2. **New content buried in UI** -- new episodes are mixed into "Continue Watching" or "Recently Added" rails with no special highlighting. Easy to miss.
3. **No "New" badge** -- content cards don't indicate whether something was recently added or updated.
4. **Series tracking absent** -- no way to "follow" a series and be alerted to new seasons/episodes.
5. **EPG changes invisible** -- for live TV, schedule changes (new premieres, special events) are not surfaced proactively.

## How StrataTV Could Address It

1. **"New" badges on content cards** -- VOD titles and series with new episodes added since the user's last visit get a "NEW" badge on their card. Cleared after the user views the detail screen.
2. **"New Episodes" rail on home screen** -- a dedicated rail showing series the user has watched that have new episodes available.
3. **Series follow/subscribe** -- a "Follow" button on series detail screens. Followed series appear in a dedicated "My Series" section with new episode indicators.
4. **EPG highlight: "Premieres Tonight"** -- a live TV rail showing first-run broadcasts and premieres from the EPG data.
5. **Optional Fire OS notifications** -- for truly important events (followed series new episode), send a system notification that the user can tap to go directly to the content. Off by default to avoid notification spam.

## Feasibility Score

**3** (moderate effort) -- Requires tracking content additions over time, comparing against watch history, and building notification infrastructure. EPG premiere detection requires parsing programme metadata for "new" or "premiere" flags.

## Validity Score

**3** (common complaint) -- More relevant for series watchers than casual viewers. IPTV users who add VOD playlists with regular updates would benefit significantly. Less relevant for live-TV-only users.

## Impact Score

**9** (Feasibility 3 x Validity 3 = 9)

## Technical Notes

- **Content change detection**: On playlist/EPG refresh (background sync per spec 33), compare incoming content with the existing Room database. New entries get `firstSeenAt = now` timestamp.
  ```kotlin
  @Entity(tableName = "vod_content")
  data class VodContentEntity(
      // ... existing fields
      val firstSeenAt: Long,     // when first seen in playlist
      val userViewed: Boolean,   // set true when user opens detail screen
  )
  ```
- **"New" badge logic**: Show badge when `firstSeenAt > lastAppOpen AND !userViewed`. Clear `userViewed` = false on each sync cycle for genuinely new content.
- **New Episodes rail query**:
  ```sql
  SELECT DISTINCT series_title FROM episodes e
  INNER JOIN watch_history w ON e.series_title = w.series_title
  WHERE e.firstSeenAt > :lastAppOpenTime
  AND w.watchedAt IS NOT NULL
  ORDER BY e.firstSeenAt DESC
  ```
- **Series follow table**:
  ```kotlin
  @Entity(tableName = "followed_series")
  data class FollowedSeriesEntity(
      @PrimaryKey val seriesTitle: String,
      val followedAt: Long
  )
  ```
- **Fire OS notifications**: Use `NotificationCompat` with `CATEGORY_RECOMMENDATION` for TV-appropriate notifications. Fire TV displays these in the notification row on the home screen.
  ```kotlin
  val notification = NotificationCompat.Builder(context, CHANNEL_ID)
      .setContentTitle("New Episode Available")
      .setContentText("$seriesTitle S${season}E${episode}")
      .setSmallIcon(R.drawable.ic_notification)
      .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
      .setContentIntent(deepLinkPendingIntent)
      .build()
  ```
- **EPG premieres**: Parse EPG `<programme>` elements for `<new />` or `<premiere />` tags, or detect first-time programme titles. Store in `EpgProgrammeEntity.isPremiere: Boolean`.
- **Fire Stick constraint**: Background sync (spec 33) must handle the notification check efficiently. Do not wake the app just for notifications; piggyback on the existing sync cycle.

## Priority Recommendation

**P3 -- Post-launch enhancement.** The "NEW" badge and "New Episodes" rail are moderate-effort, high-polish features for v2. The notification system adds complexity and should be deferred until the background sync infrastructure (spec 33) is stable. However, the `firstSeenAt` timestamp should be added to the database schema from v1 to enable future use without a migration headache.
