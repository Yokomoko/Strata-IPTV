# 11 - EPG Programme Detail Panel

## Current State

The `ProgrammeEntity` stores `title`, `description`, `start_time`, `end_time`, and `icon` for each programme. The `ChannelWithGuide` model exposes `nowTitle`, `nowDescription`, `nextTitle` to the UI.

However:
- The `nowDescription` field is populated but never displayed anywhere.
- The EPG grid's `ProgrammeCell.onClick` is a no-op: `{ /* future: show programme details */ }`.
- The channel row in the 1D guide only shows titles, not descriptions or times.
- There is no way to see programme details (description, full time range, episode info) without leaving the guide.

## Gap

Users browsing the EPG cannot see programme descriptions, full time ranges, or any detail beyond the programme title. This makes it difficult to decide whether to watch a programme, especially for unfamiliar shows.

## User Story

As a user browsing the TV Guide, I want to see full programme details (description, time, duration) when I focus on a programme, so I can decide whether to watch it.

## Acceptance Criteria

- [ ] In the 1D list view, pressing Select on a channel row (or pressing Info/Right) shows a detail panel with the NOW programme's description, time range, and duration.
- [ ] In the EPG grid view, pressing Select on a programme cell shows a detail panel for that specific programme.
- [ ] The detail panel is a semi-transparent overlay that appears on the right side of the screen (or bottom), showing:
  - Programme title
  - Time range (e.g., "18:00 - 18:30")
  - Duration (e.g., "30 min")
  - Description text (scrollable if long)
  - Channel name and logo
  - A "Watch" button that launches playback
- [ ] Pressing Back dismisses the detail panel.
- [ ] The detail panel animates in with a slide from the right.
- [ ] If no description is available, the panel shows "No description available" rather than being empty.

## Technical Approach

### Detail Panel Composable

```kotlin
@Composable
fun ProgrammeDetailPanel(
    programme: ProgrammeDetail,
    channelName: String,
    channelLogoUrl: String,
    onWatch: () -> Unit,
    onDismiss: () -> Unit,
)
```

This is an overlay within `LiveScreen`, not a separate nav destination.

### State in LiveViewModel

```kotlin
data class LiveUiState(
    ...
    val selectedProgramme: ProgrammeDetail? = null,  // null = panel hidden
)

data class ProgrammeDetail(
    val title: String,
    val description: String,
    val startTime: Instant,
    val endTime: Instant,
    val icon: String,
)
```

### Files to Modify

| File | Change |
|------|--------|
| `ui/live/LiveScreen.kt` | Add `ProgrammeDetailPanel` overlay, wire to state. |
| `ui/live/LiveViewModel.kt` | Add `selectedProgramme` state, `selectProgramme()` / `dismissProgramme()`. |
| `ui/live/GuideGridScreen.kt` | Wire `ProgrammeCell.onClick` to show detail panel. |

## Priority

**Medium** -- Improves the guide browsing experience and gives the EPG more utility. Low effort relative to impact.

## Effort Estimate

**Small (1-2h)** -- The data is already available in `ProgrammeEntity`. The panel is a straightforward Compose overlay.
