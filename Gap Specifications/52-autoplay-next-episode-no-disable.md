# 52 - Autoplay Next Episode with No Way to Disable

## Title

Next episode starts automatically with no option to turn it off

## Source

- **Reddit**: r/netflix, r/DisneyPlus, r/fireTV -- "I fell asleep watching one episode and woke up 6 episodes later." "I can't turn off autoplay on Disney+."
- **Amazon Appstore reviews**: Particularly frustrating for parents whose children's profiles binge entire series automatically.
- **Accessibility forums**: Users with motor impairments may struggle to reach the remote in the brief countdown window to cancel.
- **Data usage forums**: Users on metered connections waste data on episodes they never intended to watch.

## The Problem

While autoplay next episode is a popular binge-watching feature (covered in spec 15), it is also a significant complaint when it cannot be controlled:

1. **No disable toggle** -- some apps (Disney+ on Fire Stick historically) have no setting to turn off autoplay.
2. **Countdown too short** -- Netflix gives ~5 seconds to cancel before the next episode starts. If the remote is out of reach, you miss the window.
3. **Plays even at series/season boundaries** -- some apps auto-advance from Season 1 finale to Season 2 Episode 1, which feels wrong for shows with cliffhanger endings meant to be savoured.
4. **Children's profiles** -- autoplay enables unlimited screen time for kids. Parents want episodes to stop after each one.
5. **Credits skipped** -- the countdown overlay appears during credits, but some users want to watch the credits (especially for post-credit scenes in Marvel/anime content).
6. **No per-show control** -- it's all-or-nothing. Users may want autoplay for sitcoms but not for prestige dramas.

## How StrataTV Could Address It

1. **Autoplay is Off by default** -- respect the user's attention. Require an explicit opt-in.
2. **Global setting with clear placement** -- "Auto-play next episode: On / Off" in Settings, prominently placed under Playback.
3. **Adjustable countdown** -- if autoplay is enabled, let the user choose the countdown duration: 10s / 15s / 30s / 60s.
4. **Easy cancellation** -- pressing ANY button during the countdown cancels it (not just a specific "Cancel" button). Back, up, down, or centre all work.
5. **Respect credits** -- show the countdown overlay only after credits begin, and allow the credits to play underneath. Don't skip credits automatically.
6. **Per-profile control** -- if profiles are added later, allow different autoplay settings per profile (especially for kids).

## Feasibility Score

**1** (trivial) -- This is a configuration option on the feature in spec 15. The countdown duration is a single DataStore value. The "any button cancels" logic is a permissive key event handler.

## Validity Score

**4** (very common) -- Autoplay control is a frequently requested feature. The lack of it drives specific 1-star reviews. Parents are particularly vocal about this.

## Impact Score

**4** (Feasibility 1 x Validity 4 = 4)

## Technical Notes

- **Cross-reference**: This spec adds configuration requirements to spec `15-next-episode-autoplay.md`.
- **DataStore settings**:
  ```kotlin
  val autoPlayNextEpisode: Boolean = false         // OFF by default
  val autoPlayCountdownSeconds: Int = 15           // 10, 15, 30, 60
  ```
- **Cancellation handler**: In the `NextEpisodeCard` composable's `onKeyEvent`:
  ```kotlin
  onKeyEvent { event ->
      if (event.type == KeyEventType.KeyDown && event.key != Key.Enter) {
          // Any key except Enter cancels the countdown
          viewModel.cancelAutoPlay()
          true
      } else false
  }
  ```
  Enter/Centre still triggers "Play Now" for users who want to skip the wait.
- **Credits detection**: If chapter markers are available in the stream (HLS `#EXT-X-DATERANGE` with `CLASS=credits`), use them to trigger the countdown. Fallback: trigger at 30 seconds before end (as specified in spec 15).
- **Settings UI**: A `PlaybackSettingsScreen` group with:
  - Toggle: "Auto-play next episode" (Switch)
  - Dropdown: "Countdown duration" (only visible when autoplay is On)

## Priority Recommendation

**P1 -- Implement as part of spec 15.** These are configuration options for the autoplay feature, not a separate feature. When building spec 15, include the Off-by-default toggle, configurable countdown, and permissive cancellation from the start. The marginal effort is less than half a day.
