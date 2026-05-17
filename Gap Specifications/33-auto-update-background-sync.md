# 33 - Auto-Update / Background Sync (WorkManager Periodic Sync)

## Current State

The app already includes `work-runtime-ktx` and `hilt-work` dependencies in `build.gradle.kts`, but no `Worker` or `WorkRequest` is defined anywhere. Playlist sync is manual-only, triggered by the "Refresh Library" button in Settings. EPG fetch runs once on ViewModel init (noted in `EpgFetchService`: "Future phases will move this to WorkManager for periodic refresh"). There is no background refresh of content, EPG data, or TMDB enrichment.

## Gap

Without background sync, the library becomes stale unless the user manually refreshes. EPG data expires (the freshness gate is 2 hours), new content from the provider is missed, and TMDB metadata for newly added items is never fetched until the next manual sync. On a device that stays plugged in 24/7 like a Fire Stick, periodic background refresh is expected behavior.

## User Story

**As a Fire Stick user**, I want my content library and EPG data to refresh automatically in the background, so that I always see up-to-date channels, movies, and programme listings without manual intervention.

## Acceptance Criteria

1. A `PeriodicWorkRequest` runs playlist sync every 6 hours (configurable in Settings: 1h / 3h / 6h / 12h / 24h / Off).
2. A separate `PeriodicWorkRequest` runs EPG fetch every 4 hours (respecting the existing freshness gate in `EpgFetchService`).
3. A third periodic worker runs TMDB enrichment for any un-enriched content (movies/series with `tmdb_id = 0`).
4. Workers use `NetworkType.CONNECTED` constraint (no metered network constraint by default, but configurable).
5. Workers run with `ExistingPeriodicWorkPolicy.KEEP` to avoid duplicate enqueues.
6. Manual "Refresh Library" in Settings triggers an immediate one-shot sync that does not interfere with the periodic schedule.
7. Settings screen shows last sync timestamp and next scheduled sync time.
8. Workers respect Fire Stick power management -- they should not prevent the device from sleeping.
9. A notification-style toast or subtle status indicator shows when a background sync completes.
10. Workers are cancellable from Settings ("Disable auto-sync").

## Technical Approach

- **Workers**: Create `PlaylistSyncWorker`, `EpgSyncWorker`, and `EnrichmentWorker` extending `CoroutineWorker`. Inject dependencies via `@AssistedInject` + `HiltWorkerFactory`.
- **Scheduling**: In `StrataApp.onCreate()`, enqueue periodic work using `WorkManager.enqueueUniquePeriodicWork()` with `ExistingPeriodicWorkPolicy.KEEP`.
- **Configuration**: Store sync intervals in `SharedPreferences` (or DataStore). Read in `StrataApp` and in a `SyncConfigRepository`.
- **WorkManager + Hilt**: The `hilt-work` dependency is already present. Add `@HiltWorker` annotations and configure `HiltWorkerFactory` in `StrataApp`.
- **Fire Stick RAM**: Keep workers lightweight. The playlist sync already streams to disk; EPG fetch already streams. Enrichment worker should batch (e.g., 50 items per run) to avoid long-running workers that Fire OS might kill.
- **Status**: Use `WorkManager.getWorkInfosForUniqueWorkLiveData()` to expose sync status to the Settings ViewModel.

## Priority

**High** -- The WorkManager dependencies are already in place, and the EpgFetchService comments explicitly call this out as planned. High impact for daily usability.

## Effort Estimate

**2-3 days**

- Day 1: `PlaylistSyncWorker` + `EpgSyncWorker` with Hilt injection, periodic scheduling in `StrataApp`
- Day 2: `EnrichmentWorker`, configuration UI in Settings, interval selection
- Day 3: Status display, manual trigger integration, Fire Stick power testing, edge cases
