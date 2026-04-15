# 08 - HDMI-CEC Remote Control Mapping

## Current State

`PlayerScreen.kt` handles a limited set of key codes:
- `KEYCODE_BACK`, `KEYCODE_ESCAPE` -- exit player.
- `KEYCODE_DPAD_CENTER`, `KEYCODE_ENTER`, `KEYCODE_SPACE`, `KEYCODE_MEDIA_PLAY_PAUSE` -- toggle play/pause.
- `KEYCODE_MEDIA_REWIND`, `KEYCODE_DPAD_LEFT` -- seek backward.
- `KEYCODE_MEDIA_FAST_FORWARD`, `KEYCODE_DPAD_RIGHT` -- seek forward.

All other key codes fall through to `showControls()` and return `false` (unhandled).

There is no handling for:
- `KEYCODE_VOLUME_UP` / `KEYCODE_VOLUME_DOWN` -- volume (usually handled by the system, but CEC remotes may need explicit pass-through).
- `KEYCODE_VOLUME_MUTE` -- mute toggle.
- `KEYCODE_CHANNEL_UP` / `KEYCODE_CHANNEL_DOWN` -- dedicated channel buttons on TV remotes.
- `KEYCODE_0` through `KEYCODE_9` -- number pad (see Gap 05).
- `KEYCODE_GUIDE` / `KEYCODE_TV_INPUT` -- guide button.
- `KEYCODE_INFO` -- info/details button.
- `KEYCODE_MEDIA_STOP` -- stop playback.
- `KEYCODE_MEDIA_PLAY` / `KEYCODE_MEDIA_PAUSE` -- separate play and pause (vs. toggle).
- `KEYCODE_PROG_RED`, `KEYCODE_PROG_GREEN`, `KEYCODE_PROG_BLUE`, `KEYCODE_PROG_YELLOW` -- colour buttons.
- `KEYCODE_LAST_CHANNEL` -- return to previous channel.

## Gap

CEC-connected TV remotes send a rich set of key events that the app completely ignores. Users who control their Fire Stick via their TV remote (a very common setup -- Amazon actively promotes this) lose access to channel up/down buttons, number pad, guide button, colour buttons, and other TV-specific controls.

Fire Stick supports HDMI-CEC out of the box (Amazon calls it "HDMI Device Control"). Key events from the TV remote are forwarded to the focused app as standard Android `KeyEvent` codes.

## User Story

As a user who controls my Fire Stick via my TV's remote through HDMI-CEC, I want all the standard TV remote buttons (channel up/down, numbers, guide, info, colour buttons) to work correctly in the app, so I get a native TV experience.

## Acceptance Criteria

### Player Screen
- [ ] `KEYCODE_CHANNEL_UP` switches to the next channel (same as D-pad Up for live).
- [ ] `KEYCODE_CHANNEL_DOWN` switches to the previous channel (same as D-pad Down for live).
- [ ] `KEYCODE_0` through `KEYCODE_9` and `KEYCODE_NUMPAD_0` through `KEYCODE_NUMPAD_9` trigger channel number entry (Gap 05).
- [ ] `KEYCODE_INFO` shows the channel banner (Gap 02) or the controls overlay.
- [ ] `KEYCODE_GUIDE` opens the quick channel list (Gap 07) or exits to the full guide.
- [ ] `KEYCODE_MEDIA_STOP` exits the player (same as Back).
- [ ] `KEYCODE_MEDIA_PLAY` resumes playback. `KEYCODE_MEDIA_PAUSE` pauses. (Separate from the combined toggle.)
- [ ] `KEYCODE_LAST_CHANNEL` switches back to the previously-watched channel.
- [ ] `KEYCODE_VOLUME_UP/DOWN/MUTE` are passed through to the system (return `false` to let the framework handle hardware volume).
- [ ] `KEYCODE_PROG_RED` is mapped to toggle favourites mode (Gap 04).
- [ ] `KEYCODE_PROG_GREEN` could be mapped to toggle favourite on current channel (Gap 03).

### TV Guide Screen
- [ ] `KEYCODE_CHANNEL_UP/DOWN` scroll the channel list.
- [ ] Number keys trigger channel-number search (scroll to matching channel).
- [ ] `KEYCODE_GUIDE` toggles between list and grid view.

## Technical Approach

### Centralised Key Mapping

Rather than scattering key code handling across multiple files, create a `RemoteKeyMapper` utility:

```kotlin
object RemoteKeyMapper {
    enum class Action {
        CHANNEL_UP, CHANNEL_DOWN, CHANNEL_NUMBER,
        PLAY_PAUSE, PLAY, PAUSE, STOP,
        SEEK_BACK, SEEK_FORWARD,
        INFO, GUIDE, BACK,
        FAVOURITE_MODE, FAVOURITE_TOGGLE,
        LAST_CHANNEL,
        VOLUME_UP, VOLUME_DOWN, VOLUME_MUTE,
        UNHANDLED,
    }
    
    fun map(keyCode: Int, isLive: Boolean): Action {
        return when (keyCode) {
            KEYCODE_CHANNEL_UP -> if (isLive) CHANNEL_UP else UNHANDLED
            KEYCODE_CHANNEL_DOWN -> if (isLive) CHANNEL_DOWN else UNHANDLED
            KEYCODE_DPAD_UP -> if (isLive) CHANNEL_UP else UNHANDLED
            KEYCODE_DPAD_DOWN -> if (isLive) CHANNEL_DOWN else UNHANDLED
            in KEYCODE_0..KEYCODE_9 -> CHANNEL_NUMBER
            // ... etc
        }
    }
}
```

The `PlayerScreen` and `LiveScreen` key handlers then call `RemoteKeyMapper.map()` and switch on the action enum, keeping the actual handler logic centralised and testable.

### Last Channel

- Track `previousChannelIndex` in `PlayerViewModel`.
- When `switchChannel()` is called, store the old index before switching.
- `KEYCODE_LAST_CHANNEL` calls `switchToIndex(previousChannelIndex)`.

### Files to Modify

| File | Change |
|------|--------|
| `domain/RemoteKeyMapper.kt` | New file: centralised key-to-action mapping. |
| `ui/player/PlayerScreen.kt` | Refactor key handler to use `RemoteKeyMapper`, add all new key codes. |
| `ui/player/PlayerViewModel.kt` | Add `previousChannelIndex` tracking, `switchToLastChannel()`. |
| `ui/live/LiveScreen.kt` | Add key handling for CEC keys in the guide. |

### Testing Notes

- CEC key event testing requires a physical Fire Stick connected to a CEC-enabled TV, or the `adb shell input keyevent <code>` command for simulating key presses.
- Key code values: `KEYCODE_CHANNEL_UP = 166`, `KEYCODE_CHANNEL_DOWN = 167`, `KEYCODE_INFO = 165`, `KEYCODE_GUIDE = 172`, `KEYCODE_LAST_CHANNEL = 229`.
- Not all TVs send all key codes via CEC. The app should gracefully handle missing keys.

## Priority

**Medium** -- The Fire Stick Alexa remote covers basic navigation. CEC key support is important for users who prefer their TV remote, which is a common and growing use case. This becomes higher priority once Gap 01 is implemented.

## Effort Estimate

**Medium (half day)** -- The key mapping itself is straightforward. The main work is creating the `RemoteKeyMapper`, refactoring the existing key handler, testing with various CEC remotes, and implementing the "last channel" feature.
