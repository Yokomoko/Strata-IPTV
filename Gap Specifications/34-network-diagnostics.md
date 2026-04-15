# 34 - Network Diagnostics Screen (Speed Test, Stream Health, EPG Status)

## Current State

The app has no visibility into network health or stream quality. When a stream fails, the user sees a generic error in the player or a "Failed: ..." message on the sync subtitle. There is no way to check connection speed, test whether a specific stream URL is reachable, or verify EPG feed status. The OkHttp client has a 10s connect timeout and 60s read timeout but no diagnostics surfacing these values or failures.

## Gap

Fire Stick users frequently encounter buffering, failed streams, or stale EPG data caused by network issues (slow WiFi, ISP throttling, dead stream URLs). Without diagnostics, the user has no way to distinguish between "my WiFi is slow", "the IPTV provider is down", and "the stream URL is broken". This leads to frustration and support requests that could be self-diagnosed.

## User Story

**As a user experiencing buffering or missing EPG data**, I want a diagnostics screen that tests my connection, checks stream health, and shows EPG sync status, so that I can identify and resolve issues myself.

## Acceptance Criteria

1. A new "Network Diagnostics" destination is accessible from Settings.
2. **Connection test**: Measures download speed by fetching a known test payload (e.g., 1 MB from a CDN). Displays result in Mbps with a quality indicator (Poor / Fair / Good / Excellent).
3. **Stream probe**: User can select any channel/movie from the library. The app sends an HTTP HEAD request to the stream URL and reports: HTTP status code, response time, content type, and whether HLS manifest is reachable.
4. **EPG status**: Shows last EPG fetch time, number of programmes in the database, coverage window (earliest to latest programme time), and whether data is fresh per the 2-hour gate.
5. **Playlist status**: Shows last M3U sync time, total items per content type, and any errors from the last sync.
6. **DNS resolution time**: Displays the time to resolve the playlist host and EPG host.
7. All results are displayed in a scrollable list with clear labels, optimized for 10-foot UI readability.
8. A "Run All Tests" button executes all diagnostics sequentially with a progress indicator.
9. Results can be copied to clipboard (for pasting into a support message).
10. Screen is fully D-pad navigable.

## Technical Approach

- **Speed test**: Download a small file (Cloudflare's `speed.cloudflare.com/cdn-cgi/trace` or a self-hosted payload) using OkHttp, measure elapsed time. Calculate Mbps from bytes/time.
- **Stream probe**: `OkHttpClient.newCall(Request.Builder().url(streamUrl).head().build()).execute()` -- report status code, timing, headers.
- **EPG status**: Query `ProgrammeDao` for count, min/max `start_time`, and run the freshness check from `EpgFetchService`.
- **DNS timing**: Use `OkHttp`'s `EventListener` to capture DNS resolution time on a test request.
- **ViewModel**: `DiagnosticsViewModel` runs tests on `Dispatchers.IO`, emits results to a `StateFlow<DiagnosticsState>`.
- **UI**: `DiagnosticsScreen` composable with a `LazyColumn` of result cards. Each card shows a test name, status icon (pass/warn/fail), and detail text.
- **Navigation**: Add `Diagnostics` to the nav graph as a sub-destination of Settings, or as a ListItem in Settings that navigates to a new screen.

## Priority

**Medium** -- Valuable for troubleshooting but not blocking core functionality. Becomes more important as the user base grows.

## Effort Estimate

**2-3 days**

- Day 1: `DiagnosticsViewModel` with speed test, stream probe, and EPG status queries
- Day 2: `DiagnosticsScreen` composable, result cards, D-pad navigation
- Day 3: DNS timing, clipboard export, "Run All" flow, polish
