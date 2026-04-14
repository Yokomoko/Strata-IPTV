# 38 - Image Cache Management (Clear Cache, Set Max Size)

## Current State

The app uses Coil (`coil-compose`, `coil-network-okhttp`) for image loading. There is no custom Coil configuration -- the default `ImageLoader` is used, which caches to disk with a default size of ~250 MB (or 2% of storage, whichever is larger). On a Fire Stick with 8 GB total storage (4-5 GB usable), this can consume a significant fraction. There are no user-facing controls to view cache size, clear the cache, or set a maximum.

The app loads thousands of images: TMDB posters (w500 size), backdrops (original size), channel logos, and EPG programme icons. Over time, the disk cache grows substantially.

## Gap

Fire Stick devices have extremely limited storage (8 GB on base models, ~4-5 GB usable). An unbounded image cache can fill available storage, causing system instability, preventing other app installs, or triggering Fire OS's aggressive storage cleanup. Users need visibility into and control over cache usage.

## User Story

**As a Fire Stick user with limited storage**, I want to see how much space the image cache is using and clear it when needed, so that my device does not run out of storage.

## Acceptance Criteria

1. A new "Storage" section in Settings shows: image cache size (formatted as MB), database size, total app storage usage.
2. "Clear Image Cache" button purges the Coil disk cache and displays a confirmation with freed space.
3. User can set a maximum cache size from presets: 50 MB, 100 MB, 200 MB, 500 MB.
4. The selected max size is applied to Coil's `ImageLoader` on next app launch.
5. After clearing the cache, images reload gracefully from network on next browse (no broken images or crashes).
6. Cache size is calculated asynchronously and does not block the Settings screen render.
7. The current cache size refreshes after a clear operation.
8. Database size includes the Room database file size.
9. A "Clear All Data" option (with confirmation dialog) clears cache + database and re-triggers initial sync.
10. All controls are D-pad navigable.

## Technical Approach

- **Coil configuration**: Create a custom `ImageLoader` in `StrataApp` or a Hilt module. Configure `diskCache { maxSizeBytes(maxBytes) }` and `memoryCache { maxSizePercent(0.25) }`.
- **Cache size calculation**: Use `context.cacheDir` or the Coil disk cache directory. Calculate total size with `File.walkTopDown().sumOf { it.length() }` on `Dispatchers.IO`.
- **Clear cache**: Call `imageLoader.diskCache?.clear()` and `imageLoader.memoryCache?.clear()`.
- **Database size**: Query file size of `databases/strata-tv.db` and its WAL/SHM files.
- **Preferences**: Store max cache size in SharedPreferences. Read in the `ImageLoader` builder.
- **Hilt**: Provide the custom `ImageLoader` as a singleton. Use `ImageLoaderFactory` interface on `StrataApp` for Coil's auto-discovery.
- **UI**: New "Storage" section in Settings with `ListItem` rows showing sizes. "Clear Image Cache" as a clickable `ListItem`. Cache size selector as a dialog or expandable list.

## Priority

**Medium** -- Important for Fire Stick longevity. Storage issues are a common complaint on low-storage devices.

## Effort Estimate

**1-2 days**

- Day 1: Custom `ImageLoader` with configurable disk cache, cache size calculation, clear functionality
- Day 2: Settings UI (storage section, size display, clear button, max size selector), testing
