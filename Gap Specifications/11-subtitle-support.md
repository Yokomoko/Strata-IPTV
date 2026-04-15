# 11 - Subtitle / Closed Caption Support

## Current State

The player (`PlayerScreen.kt` + `PlayerViewModel.kt`) renders a full-screen Media3 `PlayerView` with `useController = false`. ExoPlayer is initialised with `MediaItem.fromUri(streamUrl)` and no track-selection logic. The controls overlay provides play/pause, seek, and a progress bar. There is no UI or ViewModel code to detect, list, toggle, or style subtitle tracks. The `PlayerUiState` data class has no subtitle-related fields.

Media3 1.4.1 (as declared in `libs.versions.toml`) ships with built-in text-track rendering via `SubtitleView` inside `PlayerView`, but it is currently invisible because the controller is disabled and no explicit track selection is performed.

## Gap

Streams from IPTV providers frequently carry embedded subtitles (WebVTT in HLS, SRT side-car, DVB-Sub in TS containers, EIA-608/708 closed captions). Users have no way to discover these tracks, toggle them on/off, choose a language, or adjust subtitle appearance (size, colour, background). This is a baseline expectation for any premium video player and an accessibility requirement.

## User Story

> As a viewer, I want to turn subtitles on or off and choose a language so that I can follow content in a foreign language or watch without disturbing others.

## Acceptance Criteria

1. When the controls overlay is visible and a stream has at least one text track, a "Subtitles" icon button appears in the top-right action row.
2. Pressing the button opens a focus-navigable D-pad panel listing: "Off" + each available text track labelled by language name (e.g. "English", "Spanish") and format hint (e.g. "CC" for closed captions).
3. Selecting a track applies it immediately; the panel closes and the controls auto-hide timer resets.
4. Subtitle rendering uses the system accessibility caption style on Fire OS when one is configured, and falls back to Strata's own default style (white text, semi-transparent dark background, 20sp).
5. A Settings screen toggle allows overriding subtitle font size (Small / Medium / Large) and background opacity (None / 50% / 75%).
6. The user's last subtitle preference (on/off + preferred language code) persists in DataStore and is auto-applied on the next play session.
7. Works for both live and VOD content types.

## Technical Approach

1. **Track enumeration**: After `Player.STATE_READY`, query `player.currentTracks.groups` filtering for `C.TRACK_TYPE_TEXT`. Map each `TrackGroup` to a UI model containing `language`, `label`, `roleFlags` (to distinguish CC from subtitles), and the `TrackSelectionOverride` needed to select it.
2. **Track selection**: Use `player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setOverrideForType(TrackSelectionOverride(...)).build()` to apply the chosen text track, or `.clearOverridesOfType(C.TRACK_TYPE_TEXT).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)` for "Off".
3. **UI panel**: Add a `SubtitlePickerPanel` composable -- a vertically-scrollable `TvLazyColumn` inside a semi-transparent surface anchored to the right side of the screen (mimics Netflix's side-sheet pattern). Items use `ClickableSurfaceDefaults` for D-pad focus.
4. **PlayerUiState extension**: Add `subtitleTracks: List<SubtitleTrackInfo>`, `activeSubtitleIndex: Int?`, and `subtitlePanelVisible: Boolean`.
5. **Persistence**: Store `preferred_subtitle_lang` and `subtitles_enabled` keys in a `PlayerPreferencesDataStore`. On initialise, apply the saved preference via `TrackSelectionParameters.Builder.setPreferredTextLanguage(lang)`.
6. **Style customisation**: Use `CaptionStyleCompat` from `media3-ui` to apply size/background overrides to the `SubtitleView` embedded in `PlayerView`.
7. **Fire Stick caption settings**: Read `Settings.Secure.getString(context.contentResolver, "accessibility_captioning_enabled")` to respect system-level caption toggling.

## Priority

**P1 -- High** (accessibility requirement, baseline premium feature)

## Effort Estimate

**3-4 days**
- 1 day: track enumeration + selection logic in ViewModel
- 1 day: SubtitlePickerPanel composable + D-pad navigation
- 0.5 day: DataStore persistence + auto-apply on init
- 0.5-1 day: caption style customisation + Fire OS system settings integration + testing across HLS/TS streams
