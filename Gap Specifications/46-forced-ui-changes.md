# 46 - Forced App Updates That Change the UI

## Title

App updates that rearrange the interface without warning or consent

## Source

- **Reddit**: r/fireTV, r/AndroidTV, r/netflix, r/DisneyPlus -- "They updated the app and now I can't find anything." "Why did they move the search button?" "The new UI is slower than before."
- **Amazon Appstore reviews**: Spikes of 1-star reviews correlate with major UI redesigns across all streaming apps.
- **r/elderly, accessibility forums**: Older users and users with cognitive disabilities are disproportionately affected by UI changes that break learned navigation patterns.
- **Fire TV specific**: Amazon's own Fire TV OS updates frequently rearrange the home screen, adding more ad rows and moving user apps further down.

## The Problem

Streaming apps push mandatory updates that:

1. **Rearrange navigation** -- tabs, buttons, and menu items move to new locations, breaking muscle memory.
2. **Remove features** -- features users relied on disappear (e.g., Netflix removing the "My List" sort options).
3. **Add unwanted elements** -- new promotional rows, ads in the UI, "trending" sections that push user content down.
4. **No opt-out** -- users cannot stay on the previous version. Updates are forced.
5. **Performance regressions** -- new UI frameworks or features slow down older hardware like Fire Stick Lite and 2nd-gen Fire Stick.

## How StrataTV Could Address It

1. **Stable, predictable UI** -- commit to a consistent navigation structure. Major UI changes are versioned and announced in release notes.
2. **User-controlled home screen** -- let users choose which rails appear on the home screen and in what order (spec 21-recommendations style rails vs. custom arrangement).
3. **Minimal chrome** -- fewer UI elements means fewer things to break or rearrange. Focus on content, not UI decoration.
4. **Optional updates** -- as a sideloaded/self-distributed app, we control our own update mechanism. Allow users to defer updates or read release notes before installing.
5. **Settings export/import** -- if a user has carefully configured their layout, settings backup (spec 35) preserves it across updates.
6. **Performance regression testing** -- test every release on Fire Stick Lite (weakest supported device) to prevent performance degradation.

## Feasibility Score

**2** (low effort for the policy; moderate for implementation) -- The core commitment is a design philosophy decision. User-controlled home screen ordering requires a sortable list UI and persisted ordering, but is not complex.

## Validity Score

**4** (very common) -- Every streaming app user has experienced a frustrating forced UI change. Particularly painful for non-technical users and elderly viewers who rely on learned patterns.

## Impact Score

**8** (Feasibility 2 x Validity 4 = 8)

## Technical Notes

- **Home screen rail ordering**: Store a `List<RailConfig>` in DataStore with `id`, `type`, `position`, `visible` fields. Home screen composable reads this list and renders rails in the user's preferred order.
  ```kotlin
  data class RailConfig(
      val id: String,           // "continue_watching", "favourites", "live_now", etc.
      val type: RailType,
      val position: Int,
      val visible: Boolean
  )
  ```
- **Rail management UI**: A Settings sub-screen with a reorderable list (drag-and-drop via D-pad Up/Down + Select to grab/drop) showing all available rails with toggle switches for visibility.
- **Update mechanism**: Since StrataTV is sideloaded, updates are user-initiated. The app can check for updates via a simple version endpoint and show a non-intrusive notification ("Update available: v2.1 -- tap to see what's new") without forcing the update.
- **Changelog**: In-app "What's New" screen shown once after each update, listing changes with screenshots.
- **Fire Stick constraint**: Home screen rail composition must remain lazy-loaded (`TvLazyColumn` with `TvLazyRow` items) regardless of ordering, to keep memory bounded on 1GB Fire Stick devices.

## Priority Recommendation

**P2 -- Design philosophy from day one; customisation features in v2.** The stable UI commitment costs nothing and should be a core design principle. The customisable home screen and update mechanism can be built incrementally. Marketing angle: "Your TV app, your layout. We don't rearrange your furniture."
