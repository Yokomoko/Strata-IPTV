# 32 - Multiple Playlist Sources (Add/Manage Multiple M3U URLs)

## Current State

The app is hard-coded to a single M3U playlist URL in `AppConfig.PLAYLIST_URL`. The `BootstrapRepository` creates exactly one row in the `sources` table and all sync operations reference that single source. The Settings screen displays the URL as read-only text. There is no way for the user to add, edit, remove, or switch between playlist providers.

The `SourceEntity` schema already supports multiple rows (auto-generated `id`, per-row `playlist_url` and `epg_url`), but the code never creates more than one.

## Gap

IPTV users commonly subscribe to multiple providers or maintain a personal playlist alongside a paid subscription. The single hard-coded URL prevents users from managing their own sources and forces a code change + rebuild to switch providers.

## User Story

**As an IPTV user**, I want to add and manage multiple M3U playlist URLs from the Settings screen, so that I can aggregate content from different providers into a single library.

## Acceptance Criteria

1. A new "Manage Playlists" section in Settings shows all configured sources as a list.
2. Each source row displays: a user-given label, the masked URL, last sync timestamp, and item count.
3. "Add Playlist" button opens a dialog where the user can enter a label, M3U URL, and optional EPG URL.
4. URL input supports paste from clipboard (Fire Stick supports clipboard via ADB or companion app).
5. Each source can be edited (label, URL, EPG URL) or deleted via a context menu.
6. Deleting a source shows a confirmation dialog and removes all associated content from the library.
7. "Sync All" button triggers a sequential sync of all sources.
8. Individual sources can be synced independently.
9. The home screen aggregates content from all sources; content is tagged with source label for disambiguation.
10. `AppConfig.PLAYLIST_URL` becomes a default/seed value for first launch only, not a hard-coded dependency.

## Technical Approach

- **BootstrapRepository**: Refactor `ensureSource()` to seed from `AppConfig` only on first launch (empty `sources` table). Remove all other `AppConfig.PLAYLIST_URL` references from runtime code.
- **SourceDao**: Add `watchAll(): Flow<List<SourceEntity>>`, `deleteById(id: Int)`, `update(entity: SourceEntity)`.
- **SyncService**: Change `syncFromUrl` to accept a `SourceEntity` parameter. Add `syncAll()` that iterates all sources. Track progress per-source.
- **Content cleanup**: When a source is deleted, delete all `ContentItemEntity` rows where `source_id` matches, cascading to channels/movies/series/episodes via the `content_id` foreign key.
- **UI**: New `PlaylistManagementScreen` composable with a `LazyColumn` of source cards. `AddPlaylistDialog` with `TextField` inputs and D-pad-friendly focus management.
- **Text input on Fire Stick**: Use `BasicTextField` with `focusRequester` to trigger the system keyboard. For URL entry, consider a QR code scan flow (display QR on screen, scan with phone) as a future enhancement.

## Priority

**High** -- Core functionality for any IPTV app. Currently requires a developer to change a constant and rebuild.

## Effort Estimate

**3-4 days**

- Day 1: Refactor `BootstrapRepository` and `SyncService` to be multi-source aware
- Day 2: DAO additions, cascade delete logic, per-source sync progress
- Day 3: Playlist management UI (list, add dialog, edit, delete confirmation)
- Day 4: Home screen aggregation, source labels on content, edge cases, testing
