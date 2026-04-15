# 05 - Channel Number Entry (Direct Tune)

## Current State

`SkyChannelNumbers.kt` maps ~60 UK channel names to their Sky channel numbers (e.g., BBC One = 101, ITV = 103, Sky Sports Main Event = 401). These numbers are displayed in the TV Guide rows when available.

However, there is no mechanism to type a channel number to jump directly to that channel. The Fire Stick remote does not have a number pad, but:
- HDMI-CEC connected TV remotes often have number buttons (0-9) that forward key events to the Fire Stick.
- The Fire Stick Alexa remote could theoretically receive voice commands ("go to channel 101").
- Some Bluetooth remotes paired with Fire Stick have number pads.

Android receives these as `KEYCODE_0` through `KEYCODE_9` and `KEYCODE_NUMPAD_0` through `KEYCODE_NUMPAD_9`.

## Gap

Number keys are not handled anywhere in the codebase. When a CEC-connected TV remote sends number key events, they are ignored by the player and the guide.

## User Story

As a user with a TV remote that has number buttons (via HDMI-CEC), I want to type a channel number (e.g., 101) to jump directly to that channel, so I can switch channels the traditional way.

## Acceptance Criteria

- [ ] Pressing a number key (0-9) while watching live TV starts a channel number entry sequence.
- [ ] A small number display overlay appears showing the digits entered so far (e.g., "1", "10", "101").
- [ ] After a 2-second timeout with no further digit, the entered number is resolved to a channel and playback switches to it.
- [ ] If the entered number matches a known channel, playback switches immediately. If no match, a brief "Channel not found" message appears and the overlay dismisses.
- [ ] Pressing number keys in the TV Guide also works -- it scrolls the guide to the matching channel and highlights it.
- [ ] The number entry overlay shows the number in large white text with a semi-transparent dark background, positioned at the top-right of the screen (traditional TV OSD position).
- [ ] Pressing Back or any non-number key during entry cancels the number entry.
- [ ] Three-digit numbers are resolved immediately without waiting for the timeout (Sky channel numbers are 3 digits).

### Channel Number Resolution

- First, try exact match against `ChannelEntity.channelNumber`.
- If no exact match, try `SkyChannelNumbers.numberFor()` reverse lookup (number -> channel name -> channel in list).
- If still no match, display "Channel XXX not found".

## Technical Approach

### Number Entry Buffer in PlayerViewModel

```kotlin
private var numberBuffer = ""
private var numberEntryJob: Job? = null

fun onNumberKey(digit: Int) {
    numberBuffer += digit.toString()
    _uiState.update { it.copy(channelNumberEntry = numberBuffer) }
    
    // Immediate resolve if 3 digits (Sky convention)
    if (numberBuffer.length >= 3) {
        resolveChannelNumber()
        return
    }
    
    // Start/reset 2-second timeout
    numberEntryJob?.cancel()
    numberEntryJob = viewModelScope.launch {
        delay(2_000)
        resolveChannelNumber()
    }
}

private fun resolveChannelNumber() {
    val number = numberBuffer.toIntOrNull() ?: return
    numberBuffer = ""
    _uiState.update { it.copy(channelNumberEntry = null) }
    
    val index = channelList.indexOfFirst { it.channelNumber == number }
    if (index >= 0) {
        switchToIndex(index)
    } else {
        _uiState.update { it.copy(channelNotFound = number) }
    }
}
```

### Key Handling in PlayerScreen

```kotlin
in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> {
    if (isLive) {
        viewModel.onNumberKey(event.nativeKeyEvent.keyCode - KeyEvent.KEYCODE_0)
        true
    } else false
}
KeyEvent.KEYCODE_NUMPAD_0, ..., KeyEvent.KEYCODE_NUMPAD_9 -> {
    // Same, offset from KEYCODE_NUMPAD_0
}
```

### Number Entry Overlay in PlayerScreen

A simple `AnimatedVisibility` composable at the top-right showing the accumulated digits in large (48sp) bold white text on a rounded dark background.

### Files to Modify

| File | Change |
|------|--------|
| `ui/player/PlayerViewModel.kt` | Add `onNumberKey()`, `resolveChannelNumber()`, number buffer state. |
| `ui/player/PlayerScreen.kt` | Handle `KEYCODE_0..9` and `KEYCODE_NUMPAD_0..9`, add number entry overlay composable. |
| `ui/player/PlayerViewModel.kt` (state) | Add `channelNumberEntry: String?` and `channelNotFound: Int?` to `PlayerUiState`. |
| `ui/live/LiveScreen.kt` | (Optional) Handle number keys in the guide to scroll-to-channel. |

### Dependency on SkyChannelNumbers

The `SkyChannelNumbers` map currently only maps name -> number. A reverse lookup (number -> name) is needed. Add:
```kotlin
fun channelForNumber(number: Int): String? = reverseMap[number]
```
where `reverseMap` is pre-built from the existing map.

## Priority

**Medium** -- Only useful for users with CEC-connected TV remotes that have number pads. The Fire Stick Alexa remote does not have number buttons. However, for users who do have this setup, it is a significant quality-of-life feature.

## Effort Estimate

**Small (1-2h)** -- The number buffer, timeout, and resolution logic are simple. The overlay is a minimal composable. The main work is testing with actual CEC key events on a Fire Stick connected to a TV with a number-pad remote.
