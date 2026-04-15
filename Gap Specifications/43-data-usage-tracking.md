# 43 - Data Usage Tracking (Bandwidth Monitoring)

## Current State

The app has no awareness of how much network bandwidth it consumes. IPTV streams, TMDB enrichment API calls, M3U playlist downloads (~50-150 MB), EPG XMLTV downloads (~156 MB as noted in `EpgFetchService`), and image loads all consume significant bandwidth. The OkHttp client is shared across all operations but provides no usage tracking. There is no `TrafficStats` integration or any UI showing data consumption.

## Gap

Fire Stick devices typically connect via home WiFi, but some users have metered connections or ISP data caps. A single EPG fetch can be 156 MB, and continuous IPTV streaming at 5 Mbps uses ~2.25 GB/hour. Without visibility into data usage, users cannot manage their consumption or identify unexpected bandwidth spikes (e.g., a background sync they did not expect).

## User Story

**As a user with a metered internet connection**, I want to see how much data the app has used, broken down by category (streaming, sync, enrichment, images), so that I can manage my bandwidth consumption.

## Acceptance Criteria

1. A "Data Usage" section in Settings shows total bandwidth consumed since last reset, broken down by category:
   - Streaming (video playback)
   - Playlist sync (M3U downloads)
   - EPG sync (XMLTV downloads)
   - Enrichment (TMDB API calls)
   - Images (Coil poster/backdrop downloads)
2. Data is shown for configurable periods: Today, This Week, This Month, All Time.
3. Each category shows the total in human-readable format (MB or GB, context-appropriate).
4. A "Reset Statistics" button clears the counters with confirmation.
5. An optional "Data Warning" threshold can be set (e.g., 10 GB/month). When exceeded, a banner appears on the home screen.
6. Usage data persists across app restarts.
7. Streaming data tracks actual bytes received (not estimated from bitrate).
8. Background sync data (WorkManager, when implemented) is tracked separately from manual sync.
9. A simple bar chart or percentage breakdown provides visual context.
10. Tracking overhead is minimal (no noticeable performance impact).

## Technical Approach

- **OkHttp Interceptor**: Create a `DataUsageInterceptor` implementing `Interceptor` that measures `response.body?.contentLength()` or counts actual bytes read. Tag each request with a category (sync, enrichment, EPG) using a custom header or request tag.
- **ExoPlayer bandwidth**: Use `ExoPlayer.getBandwidthMeter()` or a custom `TransferListener` on the `DataSource.Factory` to track streaming bytes.
- **Coil**: Add an OkHttp interceptor to Coil's dedicated `OkHttpClient` (or the shared one) to track image download bytes separately.
- **Storage**: Create a `data_usage` Room table with columns: `category`, `date`, `bytes_downloaded`, `bytes_uploaded`. Aggregate by period in queries.
- **Alternatively**: Use Android's `TrafficStats.getUidRxBytes(uid)` for total app-level tracking (simpler but no per-category breakdown).
- **UI**: New `DataUsageScreen` accessible from Settings. Use `LazyColumn` with category cards showing MB/GB values. Optional stacked bar visualization using Compose Canvas.
- **Performance**: The interceptor adds negligible overhead (a counter increment per request). Batch writes to the database (accumulate in memory, flush every 60 seconds or on app pause).

## Priority

**Low** -- Niche use case for metered connections. Most Fire Stick users are on unlimited home broadband. Useful for awareness but not a core feature.

## Effort Estimate

**2-3 days**

- Day 1: `DataUsageInterceptor` for OkHttp, ExoPlayer transfer listener, storage schema
- Day 2: Category tagging for sync/enrichment/EPG/images, aggregation queries
- Day 3: Data Usage UI screen, period selector, data warning threshold, bar chart
