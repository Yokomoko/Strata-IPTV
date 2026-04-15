# 61 - Inconsistent Audio Volume Between Content and Ads/Intros

## Title

Volume jumps between content, channels, and app interface sounds

## Source

- **Reddit**: r/fireTV, r/cordcutters, r/AndroidTV -- "Switching channels blasts my ears because one channel is 3x louder." "Netflix autoplay trailers are way louder than the show I was watching." "Ads on ad-supported tiers are deafeningly loud."
- **Amazon Appstore reviews**: Volume inconsistency is a long-standing complaint across all streaming and IPTV apps.
- **r/IPTV**: IPTV playlists aggregate streams from many providers, each with different audio levels. Volume normalisation is a top feature request.
- **Accessibility**: Users with hearing aids or cochlear implants are particularly sensitive to sudden volume changes.

## The Problem

Audio volume is inconsistent across streaming content:

1. **Channel-to-channel variance** -- switching between IPTV channels can produce dramatic volume differences. One channel is whisper-quiet, the next is painfully loud.
2. **Ad volume spikes** -- ad-supported tiers (Prime Video, Hulu) play ads at higher volume than the content, a practice regulated but not eliminated by CALM Act (US) / Ofcom rules (UK).
3. **Trailer volume** -- autoplay trailers and previews on browse screens are louder than normal content.
4. **Dynamic range issues** -- films with wide dynamic range have quiet dialogue and loud explosions. This is particularly problematic for late-night viewing.
5. **No normalisation** -- streaming apps rarely offer audio normalisation or volume levelling options.

## How StrataTV Could Address It

1. **Audio normalisation toggle** -- a setting that enables loudness normalisation across all content. ExoPlayer/Media3 does not have built-in normalisation, but we can use an `AudioProcessor` or leverage the device's audio framework.
2. **Per-channel volume memory** -- remember a volume offset for each channel. If the user adjusts volume on channel X, remember that offset and apply it next time they tune to channel X.
3. **Night mode / Dynamic range compression** -- a "Night Mode" audio preset that compresses dynamic range, boosting quiet dialogue and limiting loud effects. Ideal for late-night viewing.
4. **Volume boost** -- an option to boost audio output by up to +6dB for channels/content with low source volume.

## Feasibility Score

**4** (hard) -- True audio normalisation requires real-time audio processing, which is CPU-intensive on Fire Stick. Per-channel volume memory is simpler but still requires careful audio routing. Dynamic range compression requires an `AudioProcessor` pipeline that may not work reliably across all Fire OS versions.

## Validity Score

**4** (very common) -- Volume inconsistency is one of the oldest and most universal TV complaints, predating streaming entirely. It is especially acute for IPTV where streams from different providers have wildly different mastering levels.

## Impact Score

**16** (Feasibility 4 x Validity 4 = 16)

## Technical Notes

- **Per-channel volume offset** (simplest approach):
  ```kotlin
  @Entity(tableName = "channel_preferences")
  data class ChannelPreferenceEntity(
      @PrimaryKey val channelId: String,
      val volumeOffsetDb: Float = 0f,  // -6.0 to +6.0 dB
  )
  ```
  Apply via `player.volume = baseVolume * 10f.pow(offsetDb / 20f)` (converting dB to linear gain).
  Limitation: `player.volume` only adjusts the app-level volume, not the system volume. Range is 0.0 to 1.0, so boosting beyond system volume is not possible with this API alone.

- **AudioProcessor approach** (advanced):
  Media3 supports custom `AudioProcessor` instances:
  ```kotlin
  class VolumeNormalizingProcessor : AudioProcessor {
      // Measure RMS loudness of audio buffers
      // Apply gain adjustment to target -23 LUFS (EBU R128 standard)
  }
  ```
  This is CPU-intensive and may cause audio latency on Fire Stick's limited processor. Needs careful profiling.

- **Night mode / DRC**:
  Android's `AudioEffect` API provides `LoudnessEnhancer` and `DynamicsProcessing` (API 28+):
  ```kotlin
  val loudnessEnhancer = LoudnessEnhancer(player.audioSessionId)
  loudnessEnhancer.setTargetGain(300) // +3 dB boost for quiet content
  loudnessEnhancer.enabled = true
  ```
  `DynamicsProcessing` can implement compression, but is API 28+ (Fire OS 7+, Fire Stick 4K and later).

- **Fire Stick constraint**: Real-time audio processing adds CPU load. The Fire Stick's quad-core ARM processor may struggle with complex DSP while simultaneously decoding video. Profile carefully and offer normalisation as an opt-in feature.
- **Settings UI**: Under Audio settings:
  - Toggle: "Volume normalisation" (Off / On)
  - Toggle: "Night mode (reduce loud sounds)" (Off / On)
  - Note: "These features use additional processing power and may affect performance on some devices."

## Priority Recommendation

**P3 -- Investigate for v2.** The per-channel volume offset is feasible for v1 (low effort, moderate value). True normalisation and night mode require deeper audio pipeline work and careful Fire Stick performance testing. Start with the simple volume offset, gather user feedback, then invest in the advanced features if demand warrants.
