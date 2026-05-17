# 44 - No Way to Mark Content as "Not Interested" (Competitive Grievance)

## Title

No way to permanently hide content you will never want to watch

## Source

- **Reddit**: r/netflix, r/DisneyPlus, r/PleX -- "I've seen this recommendation 100 times and I will never watch it. Let me hide it." Threads appear weekly.
- **Amazon Appstore reviews**: Users complain about the same titles appearing in every row on the home screen.
- **r/cordcutters**: Particularly common from users of multiple services who see the same promoted content repeatedly.
- **Plex/Emby/Jellyfin forums**: Even self-hosted users request "hide from recommendations" features.

## The Problem

Major streaming apps aggressively recommend content but provide no way (or a deeply buried way) to say "I will never watch this, stop showing it to me":

1. **Netflix**: Has a thumbs-down button, but thumbs-down content still appears in browse rows, just ranked slightly lower. There is no "hide forever" option.
2. **Disney+, Apple TV+**: No "not interested" mechanism at all.
3. **Prime Video**: Has a "hide" option on web but not consistently on Fire Stick TV app.
4. **Plex**: "Not Interested" exists but only affects the home recommendations, not search results or genre pages.

Users are frustrated by:
- Seeing the same unwanted content in every rail on the home screen.
- Kids' content appearing for adult profiles (and vice versa).
- Content in languages they don't speak clogging recommendations.
- No way to clean up the browse experience to show only relevant content.

## How StrataTV Could Address It

1. **Long-press "Hide" action** -- on any content card, long-press (or press Menu button) to reveal a context menu with "Hide this title" and "Hide all from this category/provider".
2. **Hidden content database** -- maintain a `hidden_content` table in Room with the content ID and reason.
3. **Global filter** -- hidden content is excluded from all rails, search results, and recommendations. It is truly gone, not just deprioritised.
4. **Hidden content management** -- a Settings screen listing all hidden titles with the ability to unhide them.
5. **Category/language filters** -- allow bulk hiding by category ("Hide all Sports") or language ("Hide all Spanish content") at the playlist/EPG level.

## Feasibility Score

**2** (low effort) -- A Room table, a DAO query filter, a context menu composable, and a settings screen. Straightforward database and UI work.

## Validity Score

**4** (very common) -- Affects heavy users of every streaming platform. Particularly relevant for IPTV where playlists can contain hundreds of channels and VOD titles across languages and genres the user doesn't want.

## Impact Score

**8** (Feasibility 2 x Validity 4 = 8)

## Technical Notes

- **Database**: Add `HiddenContentEntity` to Room:
  ```kotlin
  @Entity(tableName = "hidden_content")
  data class HiddenContentEntity(
      @PrimaryKey val contentId: String,
      val title: String,        // for display in management screen
      val hiddenAt: Long,       // epoch millis
      val reason: String?       // "manual", "category:Sports", "language:es"
  )
  ```
- **DAO**: `HiddenContentDao` with `insert`, `delete`, `getAll`, and critically `getAllContentIds(): Flow<Set<String>>` for efficient filtering.
- **Filtering**: In each ViewModel that loads content rails (Home, Live, VOD), apply `.filter { it.contentId !in hiddenIds }` before emitting to UI state. Use a shared `HiddenContentRepository` injected via Hilt.
- **Context menu**: Compose for TV's `DropdownMenu` or a custom `ModalSurface` anchored to the focused card. Trigger via `onLongClick` or the remote's Menu/hamburger key (`KEYCODE_MENU`).
- **Bulk operations**: "Hide all from category X" inserts a special entry with `reason = "category:X"`. The filter checks both exact content IDs and category rules.
- **Fire Stick performance**: The hidden content ID set should be cached in memory (a `StateFlow<Set<String>>`) to avoid repeated DB queries during scrolling. Typical size will be <1000 entries, negligible memory.

## Priority Recommendation

**P2 -- Implement after core browse and playback features.** High impact, low effort. This is a "thoughtful touch" feature that users will notice and appreciate, especially power users who curate their viewing experience. Could be a standout feature in app store marketing.
