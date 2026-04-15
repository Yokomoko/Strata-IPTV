# 58 - Audio Description Unavailable or Hard to Find

## Title

Audio descriptions for visually impaired users are missing or buried in settings

## Source

- **Reddit**: r/Blind, r/Accessibility, r/fireTV -- "I can't find the audio description track on Disney+." "Netflix has AD but the toggle is different on every device."
- **Accessibility advocacy groups**: The American Council of the Blind, RNIB (UK), and similar organisations regularly publish reports on streaming app accessibility gaps.
- **Amazon Appstore reviews**: Accessibility-focused reviews cite inconsistent audio description support.
- **FCC/Ofcom regulations**: US and UK law requires a minimum amount of audio-described content on major platforms. Enforcement is increasing.

## The Problem

Audio description (AD) -- narrated descriptions of visual elements for blind and visually impaired viewers -- is poorly handled:

1. **Hard to discover** -- the AD track is buried in audio settings, often requiring 5+ D-pad presses to reach. Some apps don't indicate whether AD is available before playback starts.
2. **Inconsistent naming** -- labelled as "Audio Description", "Descriptive Audio", "AD", "English (Audio Description)", or "English [Original] (AD)" across different apps. No standard.
3. **No persistent preference** -- selecting AD on one title doesn't carry over to the next. Users must re-enable it for every piece of content.
4. **Missing on many titles** -- even when available on mobile, the AD track may not be present in the TV app version.
5. **No AD indicator in browse** -- no way to filter or identify AD-available content while browsing. Blind users must start each title, navigate to audio settings, and check.

## How StrataTV Could Address It

1. **Persistent AD preference** -- once a user enables audio description, it stays enabled for all future content. Store in DataStore, apply automatically via `TrackSelectionParameters.setPreferredAudioRoleFlags(C.ROLE_FLAG_DESCRIBES_VIDEO)`.
2. **AD badge on content cards** -- content with an AD track gets a visible "AD" badge, allowing sighted helpers to identify AD-available content.
3. **Quick-access toggle** -- an "Audio Description" quick toggle in the player controls overlay, not buried in a submenu. One press to toggle.
4. **AD filter in browse** -- a filter option on browse screens: "Show only AD-available content".
5. **Accessible audio track picker** -- the audio track selection panel (spec 12) should use clear, consistent labelling with TalkBack-compatible content descriptions.

## Feasibility Score

**2** (low effort) -- Track detection and selection is part of the existing audio track spec (12). The persistent preference is a DataStore value. The AD badge requires detecting AD tracks in advance (possible only if the stream manifest is pre-parsed).

## Validity Score

**3** (important but niche) -- Directly affects blind and visually impaired users (~2% of population). However, accessibility compliance is legally required in many markets, and supporting AD well generates strong positive press and community goodwill.

## Impact Score

**6** (Feasibility 2 x Validity 3 = 6)

## Technical Notes

- **Cross-reference**: Extends spec `12-audio-tracks.md` with accessibility-specific requirements.
- **AD track detection**: After `Player.STATE_READY`, check tracks for `C.ROLE_FLAG_DESCRIBES_VIDEO`:
  ```kotlin
  val hasAudioDescription = player.currentTracks.groups.any { group ->
      group.type == C.TRACK_TYPE_AUDIO &&
      (0 until group.length).any { i ->
          group.getTrackFormat(i).roleFlags and C.ROLE_FLAG_DESCRIBES_VIDEO != 0
      }
  }
  ```
- **Persistent preference**:
  ```kotlin
  // In PlayerViewModel.initialize()
  val preferAD = playerPrefs.preferAudioDescription.first()
  if (preferAD) {
      player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
          .setPreferredAudioRoleFlags(C.ROLE_FLAG_DESCRIBES_VIDEO)
          .build()
  }
  ```
- **AD badge**: Detecting AD availability before playback requires parsing the HLS/DASH manifest. For HLS:
  ```
  #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="English (AD)",CHARACTERISTICS="public.accessibility.describes-video"
  ```
  This could be done during background content indexing, but adds complexity. A simpler approach: show the AD badge only after the user has played the content once (cache the track availability in Room).
- **TalkBack support**: All buttons and cards must have `contentDescription` attributes. The AD toggle should announce "Audio Description: On/Off" via `LiveRegion.Polite`.
- **Fire Stick constraint**: TalkBack (screen reader) is available on Fire OS via Settings > Accessibility > VoiceView. Ensure all custom composables are compatible.

## Priority Recommendation

**P2 -- Implement alongside spec 12 (audio tracks).** The persistent AD preference and quick-access toggle add minimal work on top of the audio track picker. The AD badge on content cards is a nice-to-have for v2. Accessibility compliance is increasingly regulated, so building this early avoids retrofit costs later.
