# 64 - Poor Support for Third-Party Remotes and Keyboards

## Title

App does not respond to media keys, Bluetooth keyboard shortcuts, or third-party remotes

## Source

- **Reddit**: r/fireTV, r/AndroidTV -- "My Logitech Harmony remote's media keys don't work in Netflix." "Connected a Bluetooth keyboard and none of the shortcuts work." "CEC volume control doesn't work with this app."
- **Amazon Appstore reviews**: Compatibility complaints with third-party remotes and HDMI-CEC.
- **r/IPTV**: Power users often use mini Bluetooth keyboards or universal remotes with IPTV apps.
- **Accessibility**: Users with specialised input devices (switch controls, adapted remotes) need broad key event handling.

## The Problem

Streaming apps on Fire Stick have narrow input support:

1. **Media keys ignored** -- Play/Pause, Stop, Fast Forward, Rewind keys on third-party remotes are not handled by many apps.
2. **HDMI-CEC inconsistency** -- TV remote's media keys via CEC may not be passed through correctly.
3. **Number keys unused** -- number keys on Bluetooth keyboards or universal remotes could be used for direct channel entry, but no app supports this.
4. **No keyboard shortcuts** -- Bluetooth keyboard users cannot use shortcuts like Space for pause, Left/Right for seek, etc.
5. **Custom remote buttons** -- Fire TV remote's dedicated app buttons (Netflix, Disney+, etc.) are hardcoded; other apps cannot use them.

## How StrataTV Could Address It

1. **Comprehensive key event handling** -- handle ALL standard Android media key codes in the player:
   - `KEYCODE_MEDIA_PLAY_PAUSE`, `KEYCODE_MEDIA_PLAY`, `KEYCODE_MEDIA_PAUSE`
   - `KEYCODE_MEDIA_STOP`
   - `KEYCODE_MEDIA_FAST_FORWARD`, `KEYCODE_MEDIA_REWIND`
   - `KEYCODE_MEDIA_NEXT`, `KEYCODE_MEDIA_PREVIOUS` (channel up/down for live TV)
   - `KEYCODE_MEDIA_RECORD` (could trigger screenshot or bookmark)
2. **Number key channel entry** -- pressing number keys (0-9) enters a channel number directly. After a 2-second timeout, tune to the entered channel. Classic set-top-box UX.
3. **Keyboard shortcuts** -- Space = pause, M = mute, S = subtitles, A = audio track, F = fullscreen info, G = guide.
4. **MediaSession integration** -- register a `MediaSessionCompat` so that system-level media key routing works with CEC, Bluetooth, and the Fire TV remote's media keys.

## Feasibility Score

**2** (low effort) -- Key event handling is straightforward Android/Compose work. MediaSession integration is well-documented. Number key channel entry is a simple state machine.

## Validity Score

**3** (common for power users) -- Affects users with third-party remotes, Bluetooth keyboards, and universal remote setups. Standard Fire TV remote users are less affected. However, IPTV users are more likely to be power users with advanced remote setups.

## Impact Score

**6** (Feasibility 2 x Validity 3 = 6)

## Technical Notes

- **Key event handling**: In `PlayerScreen`'s `onKeyEvent` handler, add cases for all media keys:
  ```kotlin
  KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { viewModel.togglePlayPause(); true }
  KeyEvent.KEYCODE_MEDIA_PLAY -> { viewModel.play(); true }
  KeyEvent.KEYCODE_MEDIA_PAUSE -> { viewModel.pause(); true }
  KeyEvent.KEYCODE_MEDIA_STOP -> { viewModel.stop(); navigateBack(); true }
  KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { viewModel.seekForward(30_000); true }
  KeyEvent.KEYCODE_MEDIA_REWIND -> { viewModel.seekBackward(10_000); true }
  KeyEvent.KEYCODE_MEDIA_NEXT -> { viewModel.switchChannel(+1); true }
  KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { viewModel.switchChannel(-1); true }
  ```
- **Number key channel entry**:
  ```kotlin
  // In PlayerViewModel
  private var channelNumberBuffer = ""
  private var channelEntryJob: Job? = null
  
  fun onNumberKey(digit: Int) {
      channelNumberBuffer += digit.toString()
      _uiState.update { it.copy(channelEntryDisplay = channelNumberBuffer) }
      channelEntryJob?.cancel()
      channelEntryJob = viewModelScope.launch {
          delay(2000) // 2-second timeout
          tuneToChannel(channelNumberBuffer.toIntOrNull())
          channelNumberBuffer = ""
          _uiState.update { it.copy(channelEntryDisplay = null) }
      }
  }
  ```
  Display: a large semi-transparent number overlay in the centre of the screen (like classic TV channel entry).
- **MediaSession**:
  ```kotlin
  val mediaSession = MediaSession.Builder(context, player).build()
  ```
  Media3's `MediaSession` automatically handles media key routing to the connected `Player`. This single line enables CEC, Bluetooth, and system media key support.
- **Fire Stick remote**: The standard Fire TV remote sends `KEYCODE_MEDIA_PLAY_PAUSE` for the play/pause button and `KEYCODE_MEDIA_REWIND`/`KEYCODE_MEDIA_FAST_FORWARD` for the dedicated buttons. These must be explicitly handled; they do NOT trigger D-pad events.
- **Keyboard shortcut reference**: Add a hidden "Keyboard Shortcuts" help screen accessible via `KEYCODE_SLASH` + `KEYCODE_SHIFT` (i.e., `?`), following web app conventions.

## Priority Recommendation

**P1 -- Implement media key handling with the player.** The basic media key handling and MediaSession integration should ship with the initial player. Number key channel entry and keyboard shortcuts are P2 enhancements. Media key support is critical because Fire TV remote's own Play/Pause, FF, and Rewind buttons use media key codes, so this is not just a power-user feature -- it is required for the standard remote to work properly in the player.
