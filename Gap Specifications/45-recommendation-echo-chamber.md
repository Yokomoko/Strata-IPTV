# 45 - Algorithm Bubbles and Recommendation Echo Chambers (Competitive Grievance)

## Title

Recommendations showing the same types of content over and over

## Source

- **Reddit**: r/netflix, r/cordcutters -- "My Netflix home screen is 90% true crime because I watched one documentary." "Every row is the same 20 titles in different order."
- **Tech press**: Multiple articles about Netflix's "filter bubble" problem and how algorithmic recommendations narrow rather than broaden discovery.
- **r/PleX, r/jellyfin**: Users building self-hosted systems specifically to escape algorithmic curation.

## The Problem

Streaming apps use recommendation algorithms that create feedback loops:

1. **Watch one title in a genre, get overwhelmed by that genre** -- watching a single horror film causes the entire home screen to become horror-focused.
2. **Same titles in every row** -- "Because you watched X", "Trending", "Top Picks" all surface the same 15-20 titles, just with different labels.
3. **No way to reset or tune recommendations** -- users cannot say "show me more variety" or reset their recommendation profile.
4. **Algorithmic rankings hide catalogue depth** -- users never discover 80% of the available content because the algorithm only surfaces the top 20%.
5. **Loss of serendipity** -- the "channel surfing" experience of discovering something unexpected is gone.

## How StrataTV Could Address It

StrataTV's unique position as an IPTV app means we can take a fundamentally different approach to content discovery:

1. **Transparent, user-controlled organisation** -- content is organised by EPG category, provider, and recency rather than opaque algorithmic scoring. The user sees clear, honest categories.
2. **"Surprise Me" / Random channel** -- a dedicated button that picks a random live channel or VOD title, restoring the serendipity of traditional TV.
3. **Full catalogue browsing** -- every channel and VOD title is accessible via straightforward category/genre/A-Z browsing, not hidden behind algorithmic gatekeeping.
4. **No hidden ranking** -- within a category, content is sorted by clear criteria the user can change (alphabetical, recently added, most watched) rather than an opaque relevance score.
5. **"Discover" mode** -- a special rail that intentionally surfaces content from categories the user does NOT typically watch, encouraging breadth.

## Feasibility Score

**2** (low effort) -- This is primarily about UI organisation philosophy, not complex ML systems. The "Surprise Me" button and sort options are simple features. The Discover rail requires basic genre analysis of watch history.

## Validity Score

**3** (common complaint) -- More of an "enthusiast" complaint than universal. Casual viewers often like recommendations. But IPTV users tend to be more tech-savvy and value control over their experience. Highly relevant to our target audience.

## Impact Score

**6** (Feasibility 2 x Validity 3 = 6)

## Technical Notes

- **"Surprise Me" button**: Home screen hero card or FAB that calls `channels.random()` or `vodTitles.random()` and navigates directly to the player. Filter out hidden content and apply parental controls.
- **Sort options**: On any browse screen, a top-bar dropdown with sort modes: "A-Z", "Z-A", "Recently Added", "Recently Watched", "Channel Number". Stored in DataStore per-screen.
- **Discover rail**: Query `WatchHistoryDao` for genre frequency, then build a rail from the LEAST-watched genres. E.g., if 80% of watch time is Drama, surface Comedy/Documentary/Sports.
  ```kotlin
  val underrepresentedGenres = allGenres - topWatchedGenres.take(3)
  val discoverContent = contentDao.getByGenres(underrepresentedGenres, limit = 20)
  ```
- **No ML required** -- simple frequency counting and inverse weighting. This is deliberately transparent and predictable.
- **Fire Stick performance**: All sorting and filtering happens on pre-loaded lists in memory. No additional network calls.

## Priority Recommendation

**P3 -- Nice to have for v1, stronger for v2.** The core browse screens (spec 23-genre-browse) should already use transparent sorting. The "Surprise Me" and "Discover" rail are differentiating features that can be added once the core browse experience is solid. The "Surprise Me" button alone is a high-value, low-effort win.
