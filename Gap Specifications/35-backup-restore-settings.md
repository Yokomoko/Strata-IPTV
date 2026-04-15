# 35 - Backup/Restore Settings (Export/Import Watchlist, Favourites, Preferences)

## Current State

All user data (favourites, watchlist, continue watching, watch history, preferences) lives exclusively in the local Room database (`strata-tv.db`) on the Fire Stick. There is no export, backup, or restore mechanism. If the app is uninstalled (`adb uninstall`), all user data -- including TMDB enrichment that took hours to fetch -- is permanently lost. The database uses `fallbackToDestructiveMigration()`, meaning schema upgrades also destroy all data.

## Gap

Fire Stick apps are sideloaded via ADB, and `adb install -r` preserves data but `adb uninstall` does not. Users have no way to preserve their curated library state (favourites, watchlist, watch positions) across reinstalls, device resets, or device upgrades. This is especially painful given the time investment in TMDB enrichment.

## User Story

**As a user**, I want to export my favourites, watchlist, watch history, and app settings to a file, and import them on a fresh install, so that I do not lose my personalization when reinstalling or switching devices.

## Acceptance Criteria

1. "Backup" option in Settings exports user data to a JSON file saved to the Fire Stick's shared storage (`/sdcard/StrataTV/backups/`).
2. Backup includes: all `favourites` rows, all `watchlist` rows, all `continue_watching` rows, all `watch_history` rows, source URLs, and app preferences (sync intervals, parental PIN hash if set).
3. Backup does NOT include bulk content data (movies, channels, series) -- that is re-synced from the M3U source. TMDB enrichment flags (`tmdb_id`) are included so re-enrichment can skip already-processed items.
4. "Restore" option in Settings shows available backup files and imports the selected one.
5. Restore merges data (upsert, not replace) so existing data is not lost.
6. Backup file uses a human-readable JSON format with a version field for forward compatibility.
7. Backup filename includes a timestamp: `strata-backup-2026-04-13T14-30-00.json`.
8. User is shown a summary before restore: "This backup contains 42 favourites, 15 watchlist items, 200 watch history entries. Restore?"
9. Progress indicator during backup/restore operations.
10. Error handling for missing files, corrupt JSON, or version mismatches.

## Technical Approach

- **Export**: Create `BackupService` that queries relevant DAOs, serializes to a `BackupPayload` data class using `kotlinx.serialization`, and writes to `File`. Use `Environment.getExternalStorageDirectory()` or app-specific external storage.
- **Import**: Read JSON file, deserialize to `BackupPayload`, upsert rows into each table via existing DAO methods.
- **Schema versioning**: Include a `schemaVersion: Int` field in the JSON. On import, check compatibility and apply migrations if needed.
- **Permissions**: On Fire OS, `WRITE_EXTERNAL_STORAGE` / `READ_EXTERNAL_STORAGE` may be needed for shared storage. Alternatively, use `getExternalFilesDir()` which requires no permissions but is deleted on uninstall -- less useful. Consider using `/sdcard/` directly as Fire Stick does not enforce scoped storage strictly.
- **TMDB enrichment preservation**: Include a list of `contentId -> tmdbId` mappings in the backup so that after a fresh M3U sync, enrichment can skip items that were already matched.
- **UI**: Two new ListItems in Settings: "Backup Data" and "Restore Data". Restore shows a file picker dialog listing available `.json` files in the backup directory.

## Priority

**High** -- Directly addresses the known pain point of `adb uninstall` destroying enrichment data. Referenced in project memory as a critical concern.

## Effort Estimate

**2-3 days**

- Day 1: `BackupPayload` data class, `BackupService` export logic, file writing
- Day 2: Import/restore logic with upsert, file picker UI
- Day 3: TMDB ID preservation, error handling, progress indicators, testing on Fire Stick
