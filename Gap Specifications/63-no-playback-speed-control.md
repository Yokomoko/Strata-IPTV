# 63 - No Playback Speed Control on TV Apps

## Title

Cannot change playback speed on the TV app even though the mobile app supports it

## Source

- **Reddit**: r/fireTV, r/AndroidTV, r/netflix -- "Netflix has playback speed on mobile but not on Fire Stick. Why?" "I want 1.5x speed for podcasts and slow documentaries."
- **Amazon Appstore reviews**: Feature parity complaints between mobile and TV versions of streaming apps.
- **r/PleX**: Plex supports playback speed on mobile but the TV app implementation is inconsistent.
- **Podcast/lecture viewers**: Users who consume informational content (documentaries, lectures, news) at increased speed on mobile want the same on TV.

## The Problem

Playback speed control is common on mobile streaming apps but absent from TV versions:

1. **Mobile parity gap** -- Netflix, YouTube, and Plex offer 0.5x-2.0x speed on mobile but not on their TV apps.
2. **No way to speed up slow content** -- documentaries, news programmes, and talk shows are often consumed at 1.25x-1.5x by power users.
3. **No way to slow down** -- language learners watching foreign content want 0.75x speed to follow dialogue.
4. **Accessibility use case** -- users with auditory processing difficulties may benefit from slower playback speeds.

## How StrataTV Could Address It

StrataTV already has spec `13-playback-speed-control.md` planned. This gap spec validates the feature from the competitive grievance perspective:

1. **Full speed range** -- 0.5x, 0.75x, 1.0x, 1.25x, 1.5x, 1.75x, 2.0x.
2. **Pitch correction** -- maintain natural pitch at adjusted speeds (ExoPlayer's `SilenceSkippingAudioProcessor` handles this).
3. **Per-content-type defaults** -- allow different default speeds for live TV (always 1.0x) vs. VOD.
4. **Quick access** -- speed control in the player overlay, reachable within 2 D-pad presses.
5. **Persistent preference** -- remember the last used speed for VOD content.

## Feasibility Score

**1** (trivial) -- ExoPlayer supports `player.setPlaybackSpeed(speed)` natively with pitch correction. This is a one-line API call plus a UI selector.

## Validity Score

**3** (common request) -- Important for power users and specific content types (news, documentaries, lectures). Not relevant for most entertainment content or live TV.

## Impact Score

**3** (Feasibility 1 x Validity 3 = 3)

## Technical Notes

- **Cross-reference**: This spec validates the competitive angle for spec `13-playback-speed-control.md`.
- **ExoPlayer API**:
  ```kotlin
  player.playbackParameters = PlaybackParameters(speed) // speed = 0.5f to 2.0f
  ```
  Pitch correction is automatic when using ExoPlayer's default audio pipeline.
- **Live TV limitation**: Playback speed >1.0x on live streams causes the player to catch up to the live edge and then stall. Speed control should be disabled or limited for live content. <=1.0x works (introduces increasing delay from live edge).
- **UI**: A speed selector button in the player controls that cycles through speeds on each press: 1.0x -> 1.25x -> 1.5x -> 2.0x -> 0.75x -> 0.5x -> 1.0x.
- **Fire Stick constraint**: Speed adjustment requires audio resampling, which ExoPlayer handles via its built-in sonic audio processor. This adds minimal CPU overhead.

## Priority Recommendation

**P2 -- Implement as specified in spec 13.** This spec confirms the competitive motivation. The feature is trivially implementable and provides parity with mobile streaming apps while most TV apps lack it. A small but meaningful differentiator for power users.
