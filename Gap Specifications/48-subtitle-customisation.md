# 48 - Inadequate Subtitle Customisation

## Title

Cannot customise subtitle appearance (size, colour, background, position)

## Source

- **Reddit**: r/fireTV, r/AndroidTV, r/deaf, r/hardofhearing -- "Subtitles are tiny on my 65 inch TV", "White text on white scenes is unreadable", "Why can't I move subtitles higher?"
- **Amazon Appstore reviews**: Accessibility complaints across all streaming apps. Disney+ and Apple TV+ receive particular criticism for limited subtitle styling.
- **Accessibility advocacy**: The FCC mandates closed caption customisation on US television. Streaming apps on Fire Stick often fail to fully honour system-level caption settings.
- **r/PleX, r/jellyfin**: Users request subtitle styling features extensively. Plex's implementation is frequently praised as a benchmark.

## The Problem

Subtitle customisation on streaming TV apps is inadequate:

1. **Limited or no styling options** -- Disney+ and Apple TV+ offer minimal caption customisation on Fire Stick. Some apps only support "on/off" with no visual adjustments.
2. **System settings ignored** -- Fire OS has system-level caption settings (Settings > Accessibility > Captions), but many streaming apps override or ignore these preferences.
3. **White-on-light problem** -- default white subtitles without a background become invisible on bright scenes. Users struggle with outdoor/daylight footage.
4. **Size too small** -- default subtitle sizes are designed for close-range phone/tablet viewing, not a TV across the room. Elderly and visually impaired users are particularly affected.
5. **Position conflicts** -- subtitles overlay important on-screen text, credits, or lower-third graphics. No way to reposition them.
6. **No background/outline options** -- Netflix offers a text shadow but not a solid background. Disney+ has limited options. Inconsistent across platforms.

## How StrataTV Could Address It

Build comprehensive subtitle customisation that exceeds what any major streaming app offers:

1. **Font size**: Small / Medium / Large / Extra Large, with real-time preview.
2. **Font colour**: White, Yellow, Green, Cyan -- the four most readable colours on varied backgrounds.
3. **Background style**: None / Semi-transparent / Opaque black / Opaque coloured.
4. **Text outline**: None / Thin / Thick (drop shadow or stroke).
5. **Position**: Bottom (default) / Top / custom vertical offset via D-pad adjustment.
6. **System settings integration**: Read and honour Fire OS caption preferences as the default, with per-app overrides available.
7. **Live preview**: Show a sample subtitle line while adjusting settings so users can see the effect immediately.

## Feasibility Score

**2** (low effort) -- Media3's `CaptionStyleCompat` and `SubtitleView` already support all these customisations. This is primarily UI work for the settings screen plus applying the style to the player.

## Validity Score

**5** (universally needed) -- Affects all subtitle users (estimated 80%+ of streaming viewers use subtitles at least occasionally, per Netflix's own data). Critical for accessibility compliance and a legal requirement in many jurisdictions.

## Impact Score

**10** (Feasibility 2 x Validity 5 = 10)

## Technical Notes

- **Cross-reference**: This spec extends the subtitle feature in `11-subtitle-support.md` which covers basic track selection. This spec focuses specifically on the visual customisation complaints.
- **CaptionStyleCompat**: Media3 provides:
  ```kotlin
  CaptionStyleCompat(
      foregroundColor = Color.YELLOW,
      backgroundColor = Color.argb(128, 0, 0, 0),  // semi-transparent black
      windowColor = Color.TRANSPARENT,
      edgeType = CaptionStyleCompat.EDGE_TYPE_OUTLINE,
      edgeColor = Color.BLACK,
      typeface = Typeface.DEFAULT_BOLD
  )
  ```
  Apply via `subtitleView.setStyle(captionStyle)` and `subtitleView.setFractionalTextSize(0.05f)` for sizing.
- **Fire OS system captions**: Read system settings:
  ```kotlin
  val captioningManager = context.getSystemService(Context.CAPTIONING_SERVICE) as CaptioningManager
  val systemStyle = captioningManager.userStyle
  val systemEnabled = captioningManager.isEnabled
  val systemScale = captioningManager.fontScale
  ```
  Use these as defaults, allow per-app overrides stored in DataStore.
- **Settings UI**: A `SubtitleSettingsScreen` composable with a preview panel at the top showing sample text ("The quick brown fox...") rendered with current settings, and a settings list below with D-pad-navigable options.
- **Vertical position**: `subtitleView.setBottomPaddingFraction(fraction)` where fraction ranges from 0.02 (bottom) to 0.5 (middle). Store in DataStore.
- **DataStore keys**: `subtitle_font_size`, `subtitle_fg_color`, `subtitle_bg_style`, `subtitle_edge_type`, `subtitle_position`.

## Priority Recommendation

**P1 -- Implement alongside spec 11.** Subtitle customisation is an accessibility requirement and a strong differentiator. When building subtitle support from spec 11, include the full customisation settings from the start. The marginal effort over basic subtitle support is approximately 1 additional day. This feature alone could drive positive app store reviews from the accessibility community.
