# 62 - No Picture-in-Picture or Mini Player While Browsing

## Title

Cannot browse for new content while continuing to watch current content

## Source

- **Reddit**: r/fireTV, r/AndroidTV, r/cordcutters -- "I want to keep watching while I browse for what to watch next." "Why can't I have PiP on my Fire Stick like I can on my phone?"
- **r/IPTV**: "I want to preview a channel without leaving my current channel."
- **Amazon Appstore reviews**: PiP is a commonly requested feature across streaming apps on TV.
- **Tech press**: Articles about Android TV's underutilised PiP support.

## The Problem

Streaming apps force a binary choice: watch content OR browse for content. You cannot do both:

1. **Full-screen only** -- pressing Back exits the player entirely to return to browse. There is no mini player or PiP mode.
2. **Context loss** -- exiting a live channel to browse the guide means losing whatever you were watching. Returning requires navigating back and resuming.
3. **No channel preview** -- in IPTV apps, there is no way to preview what is on another channel without tuning to it fully.
4. **Android TV supports PiP** -- the platform has PiP support (API 26+), but most streaming apps do not implement it on the TV form factor.

## How StrataTV Could Address It

1. **Mini player overlay** -- when pressing Back from the player, instead of fully exiting, shrink the player to a small corner window (PiP) while showing the browse/guide underneath. The user can navigate the guide, find a new channel, and either tune to it (replacing the PiP) or return to full-screen.
2. **EPG mini-player** -- in the TV guide, show a small player window in the corner playing the currently tuned channel while the user browses the schedule.
3. **Quick return** -- pressing a dedicated button (e.g., Play/Pause) while in PiP mode returns to full-screen.
4. **Optional** -- users who prefer the simpler Back=Exit behaviour can disable PiP in settings.

## Feasibility Score

**4** (hard) -- Android TV PiP requires `enterPictureInPictureMode()` which has significant platform constraints: the PiP window size and position are system-controlled, not app-controlled. True in-app mini-player (a composable floating player) is more flexible but requires maintaining two ExoPlayer surfaces simultaneously or using a single player with surface switching, which is complex.

## Validity Score

**3** (common request) -- Frequently requested but not universally expected. Most TV users are accustomed to full-screen-only viewing. Power users and channel surfers would benefit most.

## Impact Score

**12** (Feasibility 4 x Validity 3 = 12)

## Technical Notes

- **Option A: Android PiP mode**:
  ```kotlin
  // In Activity
  override fun onUserLeaveHint() {
      if (isPlaying) {
          enterPictureInPictureMode(
              PictureInPictureParams.Builder()
                  .setAspectRatio(Rational(16, 9))
                  .build()
          )
      }
  }
  ```
  Pros: System-managed, works with Fire TV Home button.
  Cons: PiP window is outside the app, user cannot interact with the app while in PiP. Not useful for browsing within StrataTV.

- **Option B: In-app mini player** (recommended):
  Use a single `ExoPlayer` instance with a `SurfaceView` that is resized and repositioned via Compose layout:
  ```kotlin
  @Composable
  fun MiniPlayer(modifier: Modifier = Modifier) {
      AndroidView(
          factory = { context -> SurfaceView(context) },
          modifier = modifier
              .size(width = 320.dp, height = 180.dp)
              .align(Alignment.TopEnd)
              .zIndex(10f)
      )
  }
  ```
  The player continues playing while the browse UI renders underneath. Focus management must ensure the mini player doesn't steal D-pad focus from the browse grid.

- **EPG integration**: The mini player composable is shown as a fixed overlay on the `LiveScreen` guide grid. The guide scrolls independently underneath.

- **Fire Stick constraint**: Running video decode while rendering a complex Compose UI simultaneously may cause frame drops on Fire Stick Lite (1GB RAM, weaker GPU). Profile carefully. Consider limiting mini player resolution to 480p to reduce decode overhead.

- **Single ExoPlayer**: Do NOT create a second ExoPlayer instance for PiP. Fire Stick has limited hardware decoder slots. Reuse the single player and switch its surface between full-screen and mini views.

## Priority Recommendation

**P3 -- v2 feature.** This is a high-impact differentiator but technically challenging on Fire Stick hardware. The in-app mini player approach is the right direction, but requires careful performance work. Implement after the core player and browse experiences are polished. The EPG mini-player alone (showing current channel while browsing the guide) would be a significant UX win that could ship earlier than full PiP browse support.
