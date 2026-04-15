# 60 - No Built-In Sleep Timer

## Title

No sleep timer to automatically stop playback after a set time

## Source

- **Reddit**: r/fireTV, r/cordcutters, r/AndroidTV -- "I fall asleep watching TV and wake up 4 hours later with the TV still on." "Why doesn't Netflix have a sleep timer?" "I use a third-party app just for sleep timer."
- **Amazon Appstore reviews**: Sleep timer is a frequent feature request for all streaming apps.
- **r/IPTV**: IPTV users watching live TV at night want playback to stop automatically.
- **Energy/environmental forums**: Users concerned about electricity waste from all-night streaming.

## The Problem

No major streaming app has a built-in sleep timer:

1. **No native timer** -- Netflix, Disney+, Prime Video, and most streaming apps lack a sleep timer. The only options are the Fire TV system sleep timer (which turns off the entire device, not just the app) or third-party timer apps.
2. **System sleep timer is blunt** -- Fire TV's system timer powers off the device entirely, losing playback state. It also affects other activities (e.g., playing music).
3. **"Still watching?" is not a substitute** -- the "are you still watching" prompt (spec 42) is unpredictable and interruptive. It is not a user-controlled timer.
4. **Third-party workarounds** -- users resort to setting phone alarms, using smart plugs on timers, or third-party Android timer apps. All are clunky workarounds.
5. **Common use case** -- falling asleep to TV is extremely common. Users want the content to stop, the screen to dim/blank, and their position to be saved.

## How StrataTV Could Address It

1. **Built-in sleep timer** -- accessible from the player controls overlay. Options: 15m / 30m / 45m / 1h / 2h / End of current episode / End of current programme.
2. **Graceful shutdown** -- when the timer expires: save playback position, pause the player, show a dim "Playback stopped - Sleep timer" message for 10 seconds, then navigate to a black/dark home screen (not full device shutdown).
3. **Timer indicator** -- a small clock icon in the player UI showing remaining time. Non-intrusive, visible only when controls are shown.
4. **Quick re-enable** -- after the timer stops playback, pressing any button shows a "Resume?" option, allowing the user to continue watching if they're still awake.
5. **Persistent preference** -- remember the last-used timer duration for quick re-selection.

## Feasibility Score

**1** (trivial) -- A countdown coroutine, a DataStore preference, and a small UI element. Less than a day of work.

## Validity Score

**4** (very common) -- Falling asleep to TV is a near-universal behaviour. The lack of a sleep timer affects anyone who watches TV in bed, which is a large portion of Fire Stick users (bedroom TVs are a primary Fire Stick use case).

## Impact Score

**4** (Feasibility 1 x Validity 4 = 4)

## Technical Notes

- **Timer implementation**:
  ```kotlin
  // In PlayerViewModel
  private var sleepTimerJob: Job? = null
  
  fun setSleepTimer(durationMs: Long) {
      sleepTimerJob?.cancel()
      _uiState.update { it.copy(sleepTimerRemainingMs = durationMs) }
      sleepTimerJob = viewModelScope.launch {
          var remaining = durationMs
          while (remaining > 0) {
              delay(1000)
              remaining -= 1000
              _uiState.update { it.copy(sleepTimerRemainingMs = remaining) }
          }
          onSleepTimerExpired()
      }
  }
  
  private fun onSleepTimerExpired() {
      saveCurrentPosition()
      player.pause()
      _uiState.update { it.copy(sleepTimerExpired = true) }
  }
  
  fun cancelSleepTimer() {
      sleepTimerJob?.cancel()
      _uiState.update { it.copy(sleepTimerRemainingMs = null) }
  }
  ```
- **"End of episode" mode**: Instead of a fixed duration, monitor playback position. When `currentPosition >= duration - 5000` (5 seconds before end), pause after the episode ends rather than mid-content.
- **"End of programme" mode** (live TV): Use EPG data for the current programme's end time. Set the timer to `programmeEndTime - now`.
- **UI**: A `SleepTimerButton` in the player controls row (next to subtitles, audio track buttons). Shows a clock icon. On press, opens a selection panel:
  ```
  Sleep Timer
  ─────────────
  ○ 15 minutes
  ○ 30 minutes
  ○ 45 minutes
  ○ 1 hour
  ○ 2 hours
  ○ End of episode     (VOD only)
  ○ End of programme   (Live only, if EPG available)
  ○ Off
  ```
- **Timer display**: When active, show a small "💤 42m" indicator in the top-left of the player overlay. Update every minute.
- **DataStore**: `last_sleep_timer_duration: Long?` for quick re-selection.
- **Fire Stick constraint**: The countdown coroutine runs in `viewModelScope` and is automatically cancelled if the ViewModel is cleared. If the app is killed by Fire OS, the timer is lost, but so is playback -- the position was already saved.

## Cross-Reference

This competitive grievance spec validates spec `19-sleep-timer.md` which contains the full implementation specification.

## Priority Recommendation

**P2 -- Quick win, high perceived value.** This is a trivial-to-build feature that users actively search for. It has disproportionate marketing value as a bullet point: "Built-in sleep timer". Implement after the core player controls are stable.
