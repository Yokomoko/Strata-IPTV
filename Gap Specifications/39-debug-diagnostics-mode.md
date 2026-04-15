# 39 - Debug/Diagnostics Mode (Enrichment Progress, DB Stats, Error Logs)

## Current State

The app uses `Log.d` / `Log.i` / `Log.e` for logging (visible in logcat). `EpgFetchService` logs coverage reports and `SyncService` surfaces error messages to the UI via `Progress.Error`. However, there is no in-app way to view logs, enrichment progress, database statistics, or error history. The user must connect via ADB and run `adb logcat` to see any diagnostic information.

TMDB enrichment progress is tracked internally by `EnrichmentProgressTracker` but is not exposed to any UI.

## Gap

Diagnosing issues on a Fire Stick requires ADB access and logcat fluency. Most problems (enrichment stalled, EPG parse failures, database bloat) could be self-diagnosed with an in-app debug panel. This is also essential for the developer during active development without needing a laptop tethered to the Fire Stick.

## User Story

**As a developer or power user**, I want an in-app diagnostics panel that shows enrichment progress, database statistics, and recent error logs, so that I can diagnose issues without ADB.

## Acceptance Criteria

1. A "Diagnostics" option in Settings (hidden behind a long-press or 5-tap Easter egg on the version text) opens the debug panel.
2. **Enrichment progress**: Shows total movies, enriched count, pending count, current enrichment rate (items/minute), estimated time remaining.
3. **Database stats**: Shows row counts for each table (sources, content_items, channels, movies, series, episodes, programmes, favourites, watchlist, continue_watching, watch_history). Shows database file size.
4. **Sync history**: Shows timestamp and result (success/fail + item counts) for the last 10 sync operations.
5. **Error log**: Shows the last 50 errors captured during sync, enrichment, and EPG fetch. Each entry has timestamp, source (sync/enrichment/EPG), and message.
6. **EPG coverage**: Shows number of programmes, date range covered, number of matched channels vs. unmatched.
7. A "Copy to Clipboard" button serializes all diagnostics to text for sharing.
8. An "Export Logs" button writes the full diagnostics to a file on the device.
9. All data refreshes in real-time (using StateFlow collectors).
10. The panel is scrollable and D-pad navigable.

## Technical Approach

- **Error log buffer**: Create an in-memory `RingBuffer<DiagnosticEntry>` (capacity 100) singleton. Wrap `Log.e` calls in sync/enrichment/EPG to also push entries to this buffer. Inject via Hilt.
- **Database stats**: Add `@Query("SELECT COUNT(*) FROM ...")` methods to each DAO. Create a `DiagnosticsRepository` that aggregates all counts into a single data class.
- **Enrichment progress**: Expose `EnrichmentProgressTracker`'s state to the diagnostics ViewModel.
- **Sync history**: Add a `sync_log` table (or use SharedPreferences) to record each sync operation's timestamp, source, result, and counts.
- **UI**: `DiagnosticsScreen` composable with collapsible sections (Library Stats, Enrichment, EPG, Error Log). Use `LazyColumn` for the error log.
- **Easter egg**: In Settings, detect 5 rapid clicks on the "Strata TV v0.1.0" ListItem. Set a flag that reveals the Diagnostics item.
- **Clipboard**: Use `ClipboardManager` to copy formatted text.

## Priority

**Medium** -- High value for the developer during active development. Lower priority for end users, but power users appreciate it.

## Effort Estimate

**2-3 days**

- Day 1: `DiagnosticEntry` ring buffer, error capture integration, DAO count queries, `DiagnosticsRepository`
- Day 2: `DiagnosticsScreen` composable with sections, real-time refresh
- Day 3: Clipboard/export, sync history table, Easter egg activation, polish
