# 09 - Channel Preview / Picture-in-Picture in Guide

## Current State

The TV Guide (`LiveScreen.kt` and `GuideGridScreen.kt`) is a static list/grid of channel names and programme data. When browsing the guide:
- Each channel row shows: logo, channel number, name, NOW title, NEXT title.
- The EPG grid view shows programme blocks on a timeline.
- There is no video preview of any channel.
- To see what a channel looks like, the user must select it and enter the full player.

## Gap

Premium TV interfaces (Sky Q, TiVo, Samsung TV Plus) show a small video preview of the focused channel in the guide. As the user scrolls through channels, a thumbnail or live preview updates to show what's currently playing on the highlighted channel.

This is a significant UX differentiator that makes channel browsing feel alive rather than like reading a text list.

## User Story

As a user browsing the TV Guide, I want to see a small live preview of the channel I'm currently focused on, so I can peek at what's playing before committing to watch it.

## Acceptance Criteria

- [ ] A preview window appears in the top-right area of the TV Guide when a channel row is focused.
- [ ] The preview shows live video from the focused channel's stream URL.
- [ ] The preview updates when the user moves focus to a different channel (with a debounce of ~500ms to avoid rapid stream switching).
- [ ] The preview window is 320x180dp (16:9 aspect ratio), with rounded corners and a subtle border.
- [ ] The preview is muted by default (no audio from the preview).
- [ ] If the preview stream fails to load, a fallback is shown (channel logo on a dark background, or the text "Preview unavailable").
- [ ] The preview does not significantly impact guide scrolling performance.
- [ ] The preview is optional and can be disabled in Settings (for users on limited bandwidth or slow connections).
- [ ] The preview player is released when the user leaves the TV Guide or enters the full player.

## Technical Approach

### Separate ExoPlayer Instance

The preview requires a second `ExoPlayer` instance (separate from the main player). This is the standard approach used by TiVo, Plex, and other TV apps.

```kotlin
@Composable
fun ChannelPreview(
    streamUrl: String?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val previewPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            volume = 0f  // Muted
            playWhenReady = true
        }
    }
    
    LaunchedEffect(streamUrl) {
        delay(500)  // Debounce
        if (streamUrl != null) {
            previewPlayer.setMediaItem(MediaItem.fromUri(streamUrl))
            previewPlayer.prepare()
        } else {
            previewPlayer.stop()
        }
    }
    
    DisposableEffect(Unit) {
        onDispose { previewPlayer.release() }
    }
    
    AndroidView(
        factory = { PlayerView(it).apply { player = previewPlayer; useController = false } },
        modifier = modifier,
    )
}
```

### Layout Integration

In `LiveScreen.kt`, add a `Row` or `Box` layout that positions the channel list on the left (~70% width) and the preview on the right (~30% width):

```
+--------------------------------------------+------------------+
|  [Category Chips]                          |                  |
|  [Channel 1 - BBC One     NOW | NEXT]     |   [Live Preview] |
|  [Channel 2 - ITV         NOW | NEXT]     |   320 x 180      |
|  [Channel 3 - Channel 4   NOW | NEXT]     |                  |
|  ...                                       |  Channel: BBC One|
+--------------------------------------------+------------------+
```

### Focus Tracking

`LiveViewModel` needs to expose the focused channel's stream URL:
```kotlin
fun onChannelFocused(channel: ChannelWithGuide) {
    _focusedChannel.value = channel
}
```

The `LiveScreen` passes an `onFocused` callback to each `ChannelRow`:
```kotlin
ChannelRow(
    channel = channel,
    onPlay = { ... },
    onFocused = { viewModel.onChannelFocused(channel) },
)
```

### Fire Stick Constraints

**This is the riskiest feature for Fire Stick.** Key concerns:

1. **Memory**: A second ExoPlayer instance adds ~30-50 MiB to the memory footprint. On Fire Stick (which has ~1 GB usable RAM), this is significant. The memory budget note says to keep buffers under ~200 MiB total.
2. **Hardware codec**: Fire Stick has a limited number of hardware video decoder instances (typically 1-2). If the main player and preview both need hardware decoders, one may fall back to software decode, causing dropped frames.
3. **Bandwidth**: Two simultaneous streams doubles network bandwidth. On slower connections, this could cause buffering on the main stream.

### Mitigation

- Use a lower-resolution variant of the stream for the preview if the M3U playlist provides SD/HD variants.
- Set `ExoPlayer.setVideoScalingMode(C.VIDEO_SCALING_MODE_SCALE_TO_FIT)` and limit the preview surface size.
- Implement a "bandwidth budget" check: disable preview if the connection is slow.
- Make the feature opt-in via Settings, defaulting to off on Fire Stick and on for higher-powered devices.
- Consider using a static thumbnail approach instead: capture a frame from the stream and display it as an image (but this still requires briefly connecting to the stream).

### Files to Modify

| File | Change |
|------|--------|
| `ui/live/LiveScreen.kt` | Restructure layout to include preview area, add focus tracking. |
| `ui/live/ChannelPreview.kt` | New file: preview composable with debounced ExoPlayer. |
| `ui/live/LiveViewModel.kt` | Add `focusedChannel` state, `onChannelFocused()`. |
| `ui/settings/SettingsScreen.kt` | Add "Channel preview in guide" toggle. |
| `ui/settings/SettingsViewModel.kt` | Persist the preview preference. |

## Priority

**Low** -- This is a premium polish feature. The memory and codec constraints on Fire Stick make it risky to implement and may need to be disabled by default on that platform. It is the kind of feature that impresses but is not essential to the core live TV workflow.

## Effort Estimate

**Large (1+ day)** -- The composable is relatively simple, but the Fire Stick hardware constraints, dual-decoder testing, memory profiling, and edge case handling (stream failures, rapid focus changes, cleanup on navigation) make this a significant effort. Budget time for device-specific testing.
