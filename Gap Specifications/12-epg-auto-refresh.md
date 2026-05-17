# 12 - EPG Now/Next Auto-Refresh

## Current State

The `LiveViewModel.refreshGuide()` method runs:
1. Once on init (after EPG fetch).
2. When the `channelDao.watchAll()` Flow emits (channel list changes from sync).

The now/next programme resolution uses `Instant.now()` at the time of the query. This means:
- If a user has the TV Guide open for 30+ minutes, the "NOW" and "NEXT" labels become stale.
- A programme that started at 18:00 and ends at 18:30 will still show as "NOW" at 19:00 if the guide hasn't refreshed.
- The EPG grid view's "NOW" badge is computed from a `remember { Instant.now() }` that never updates.

There is no periodic refresh of the guide data while the screen is visible.

## Gap

The TV Guide becomes stale if left open. Programme transitions (NOW -> NEXT) are not reflected until the user navigates away and back, or the channel list changes.

## User Story

As a user with the TV Guide open, I want the now/next information to update automatically when programmes change, so the guide always shows accurate current programme data.

## Acceptance Criteria

- [ ] The now/next data refreshes automatically at programme boundaries (when a NOW programme ends and NEXT becomes NOW).
- [ ] At minimum, the guide refreshes every 5 minutes while visible.
- [ ] The refresh is silent (no loading indicator, no scroll position reset, no focus loss).
- [ ] The EPG grid view's "NOW" badge updates correctly on refresh.
- [ ] The refresh does NOT re-fetch the EPG from the network -- it only re-queries the local Room database with the updated `Instant.now()`.

## Technical Approach

Add a periodic ticker in `LiveViewModel`:

```kotlin
init {
    // ... existing init code ...
    
    // Periodic guide refresh (every 5 minutes) while observed.
    viewModelScope.launch {
        while (isActive) {
            delay(5 * 60 * 1_000L) // 5 minutes
            refreshGuide()
        }
    }
}
```

For a smarter approach, compute the earliest `nowEndTime` across all channels and schedule a refresh at that exact instant:

```kotlin
private fun scheduleNextRefresh() {
    val earliest = _channels.value
        .mapNotNull { it.nowEndTime }
        .minOrNull() ?: return
    
    val delayMs = Duration.between(Instant.now(), earliest).toMillis()
        .coerceIn(30_000, 5 * 60 * 1_000) // min 30s, max 5min
    
    refreshJob?.cancel()
    refreshJob = viewModelScope.launch {
        delay(delayMs)
        refreshGuide()
        scheduleNextRefresh() // Chain
    }
}
```

### Files to Modify

| File | Change |
|------|--------|
| `ui/live/LiveViewModel.kt` | Add periodic refresh timer or smart schedule-at-boundary logic. |
| `ui/live/GuideGridScreen.kt` | Replace `remember { Instant.now() }` with a parameter passed from the parent, or use `derivedStateOf` with a ticking clock. |

## Priority

**Medium** -- The guide works fine for short browsing sessions. But for users who leave the guide open (e.g., while deciding what to watch), stale data is confusing.

## Effort Estimate

**Small (1-2h)** -- The simple periodic approach is a few lines. The smart boundary-based approach is slightly more work but much more elegant.
