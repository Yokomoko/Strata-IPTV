# 12 - Audio Track Selection

## Current State

`PlayerViewModel` creates an `ExoPlayer` with default `TrackSelectionParameters`. There is no code to enumerate, display, or switch audio tracks. `PlayerUiState` contains no audio-related fields. The `PlayerScreen` controls overlay has no audio selection button.

ExoPlayer's default behaviour is to pick the first audio track (or the one matching the device locale), but the user cannot override this choice.

## Gap

IPTV streams regularly carry multiple audio tracks -- different languages, stereo vs. 5.1 surround, commentary tracks, and audio descriptions. Without an audio selector, users are locked into whichever track ExoPlayer picks by default. Premium players (Netflix, Disney+, Amazon Video) all expose explicit audio selection alongside subtitles.

## User Story

> As a viewer, I want to switch between audio tracks (e.g. English, Spanish, or surround sound) without leaving the player so that I can watch content in my preferred language and audio format.

## Acceptance Criteria

1. When the controls overlay is visible and the stream has more than one audio track, an "Audio" icon button appears in the top-right action row (adjacent to the Subtitle button from spec 11).
2. Pressing the button opens a focus-navigable D-pad panel listing each available audio track labelled with: language name, channel layout (e.g. "Stereo", "5.1 Surround"), and codec hint (e.g. "AAC", "AC3").
3. The currently active track is highlighted with the accent colour and a check icon.
4. Selecting a different track switches audio seamlessly without restarting the stream or losing the current position.
5. If the stream only has one audio track, the Audio button is hidden (no dead-end UI).
6. The user's preferred audio language persists in DataStore and is applied as a preference on subsequent plays via `setPreferredAudioLanguage`.
7. Works for live and VOD content.

## Technical Approach

1. **Track enumeration**: After `Player.STATE_READY`, query `player.currentTracks.groups` filtering for `C.TRACK_TYPE_AUDIO`. Extract `language`, `channelCount`, `sampleMimeType`, and `label` from each `Format` within the group.
2. **Track switching**: Build a `TrackSelectionOverride` targeting the selected `TrackGroup` and index, apply via `player.trackSelectionParameters`. ExoPlayer handles the codec reconfiguration internally -- no seek required.
3. **Channel layout label**: Map `channelCount` to human-readable strings: 1 = "Mono", 2 = "Stereo", 6 = "5.1 Surround", 8 = "7.1 Surround".
4. **UI panel**: Reuse the same side-sheet pattern from the subtitle picker (spec 11). Share a common `TrackPickerPanel` composable parameterised by track type.
5. **PlayerUiState extension**: Add `audioTracks: List<AudioTrackInfo>`, `activeAudioIndex: Int`, `audioPanelVisible: Boolean`.
6. **Persistence**: Add `preferred_audio_lang` to `PlayerPreferencesDataStore`. On init, apply via `TrackSelectionParameters.Builder.setPreferredAudioLanguage(lang)`.
7. **Fire Stick audio passthrough**: For AC3/EAC3 tracks on devices that support passthrough, do not force transcoding. Use `DefaultAudioSink.Builder().setAudioProcessors(emptyList())` to preserve the original bitstream for compatible AVRs.

## Priority

**P1 -- High** (core player feature, often requested alongside subtitles)

## Effort Estimate

**2-3 days**
- 0.5 day: track enumeration model + ViewModel logic
- 1 day: shared TrackPickerPanel composable + audio-specific rendering
- 0.5 day: DataStore persistence + auto-apply
- 0.5-1 day: Fire Stick audio passthrough testing + edge cases (single-track hiding, mid-stream track changes)
