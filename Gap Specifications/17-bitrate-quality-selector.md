# 17 - Bitrate / Quality Selector

## Current State

`ExoPlayer.Builder(application).build()` creates a player with default adaptive track selection. For HLS streams (the most common IPTV format), ExoPlayer's `AdaptiveTrackSelection` automatically chooses the bitrate based on available bandwidth. There is no UI to view available quality levels or override the selection. `PlayerUiState` has no quality-related fields, and the ViewModel does not interact with `TrackSelectionParameters` for video tracks.

The build includes `media3-exoplayer-hls` (confirmed in `build.gradle.kts`), so HLS ABR is functional -- the gap is user control, not capability.

## Gap

Users on Fire Stick may experience buffering on high-bitrate streams when their network cannot sustain the throughput. Conversely, users with strong connections may want to force the highest quality. Without a quality selector, users cannot diagnose whether buffering is caused by an overly-aggressive quality selection or a server-side issue. Premium players (YouTube, Netflix) always provide a manual quality override.

## User Story

> As a viewer, I want to manually select the streaming quality (Auto, 720p, 1080p, 4K) so that I can balance picture quality against buffering on my network.

## Acceptance Criteria

1. A "Quality" icon button appears in the controls overlay top-right action row when the stream has multiple video renditions.
2. Pressing the button opens a D-pad-navigable panel listing available quality levels: "Auto (recommended)" plus each available rendition labelled with resolution and bitrate (e.g. "1080p -- 8 Mbps", "720p -- 4 Mbps").
3. "Auto" is the default and uses ExoPlayer's adaptive selection. All other options lock to the selected rendition.
4. The currently active selection is highlighted. In Auto mode, a secondary label shows the currently playing resolution (e.g. "Auto (playing 1080p)").
5. Switching quality applies immediately. If the selected rendition requires buffering, the buffering spinner appears.
6. For single-bitrate streams (non-ABR), the Quality button is hidden.
7. The user's quality preference (Auto / max resolution cap) persists in DataStore and is applied on next play.

## Technical Approach

1. **Rendition enumeration**: After `Player.STATE_READY`, query `player.currentTracks.groups` filtering for `C.TRACK_TYPE_VIDEO`. For HLS ABR, each `TrackGroup` typically contains multiple `Format` entries. Extract `width`, `height`, `bitrate`, `codecs` from each `Format`.
2. **Deduplicate and sort**: Group by resolution (`height`), pick the highest-bitrate entry per resolution group, sort descending (4K > 1080p > 720p > 480p).
3. **Quality lock**: For non-Auto selections, apply `TrackSelectionParameters.Builder().setMaxVideoSize(width, height).setMinVideoSize(width, height)` to lock the resolution. For Auto, clear overrides and set `setMaxVideoSizeSd(Int.MAX_VALUE, Int.MAX_VALUE)`.
4. **Current quality indicator**: Poll `player.videoFormat?.height` periodically (every 2s) or listen to `onTracksChanged` to update the "playing at" label in Auto mode.
5. **UI panel**: Reuse the shared `TrackPickerPanel` from specs 11-12, parameterised for video quality items.
6. **PlayerUiState extension**: Add `videoQualities: List<VideoQualityInfo>`, `selectedQualityIndex: Int`, `currentPlayingResolution: String?`, `qualityPanelVisible: Boolean`.
7. **Persistence**: Store `preferred_max_resolution` (0 = Auto, 720, 1080, 2160) in DataStore. On init, apply as `setMaxVideoSize`.
8. **Fire Stick constraints**: Fire Stick 4K Max supports 2160p HEVC; Fire Stick Lite caps at 1080p. Query `MediaCodecList` at startup to determine the device's max decode resolution and filter the quality list accordingly.

## Priority

**P2 -- Medium** (important for users with inconsistent network quality; helps with Fire Stick thermal throttling situations)

## Effort Estimate

**2-3 days**
- 0.5 day: video track enumeration + resolution grouping in ViewModel
- 0.5 day: TrackSelectionParameters manipulation for quality lock/unlock
- 1 day: quality panel UI + current-quality polling + D-pad navigation
- 0.5 day: DataStore persistence + device capability detection
- 0.5 day: testing across HLS ABR, single-bitrate, and MPEG-TS streams
