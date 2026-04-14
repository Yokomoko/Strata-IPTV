# 66 - No Text Size or UI Scaling Options

## Title

Text is too small to read from across the room and there is no way to enlarge it

## Source

- **Reddit**: r/fireTV, r/AndroidTV, r/elderly -- "I can't read the episode descriptions from my couch." "Why is the text so tiny on my Fire Stick?" "My parents can't use Netflix on their TV because the text is too small."
- **Amazon Appstore reviews**: Text size complaints appear frequently, especially from older users and users with large TVs where viewing distance is significant.
- **Accessibility forums**: Low vision users need scalable UI elements. Fire OS has a system font size setting but many apps ignore it.
- **r/cordcutters**: "10-foot UI" design failures are a recurring topic.

## The Problem

Streaming app UIs are designed for close-range devices and poorly adapted for TV viewing distances:

1. **Small text** -- synopses, episode descriptions, metadata, and menu labels are too small to read from a typical 2-3 metre viewing distance.
2. **No text size setting** -- most streaming apps have no in-app text size adjustment.
3. **System font size ignored** -- Fire OS has a system-level font size setting (Settings > Display > Font Size), but many apps use fixed text sizes that ignore this preference.
4. **Dense layouts** -- information-dense layouts from phone/tablet designs are reused on TV without adaptation. Too much small text, not enough visual hierarchy.
5. **Low contrast** -- light grey text on dark backgrounds fails WCAG contrast requirements, making it harder to read at distance.

## How StrataTV Could Address It

1. **Respect system font size** -- use `sp` (scale-independent pixels) for all text, which automatically scales with the Fire OS font size setting.
2. **In-app text size option** -- a "Display Size" setting with Small / Medium (default) / Large / Extra Large options. Applies a multiplier to all text and adjustable UI elements.
3. **High contrast mode** -- a toggle that increases text contrast (pure white on pure black), thickens borders, and enlarges focus indicators.
4. **10-foot UI design principles** -- design all screens for 2-3 metre viewing distance from the start:
   - Minimum text size: 18sp for body, 24sp for titles.
   - High contrast: WCAG AA minimum (4.5:1 for body text).
   - Generous spacing between interactive elements.
   - Clear visual hierarchy with size differentiation.
5. **Metadata truncation control** -- for long descriptions, offer "Read more" expansion rather than tiny text.

## Feasibility Score

**2** (low effort) -- Using `sp` units and Compose's `LocalDensity` / `LocalFontScale` for scaling is standard Android development. A global scale multiplier can be applied via `CompositionLocalProvider`.

## Validity Score

**4** (very common) -- Affects older users, visually impaired users, and anyone with a large TV and standard viewing distance. As TV sizes increase (55", 65", 75"+), viewing distances increase, making small text increasingly problematic. This will only get worse over time.

## Impact Score

**8** (Feasibility 2 x Validity 4 = 8)

## Technical Notes

- **Global font scale**: Apply at the root composable level:
  ```kotlin
  val userFontScale = settingsViewModel.fontScale.collectAsState() // 1.0, 1.25, 1.5, 1.75
  
  CompositionLocalProvider(
      LocalDensity provides Density(
          density = LocalDensity.current.density,
          fontScale = LocalDensity.current.fontScale * userFontScale.value
      )
  ) {
      // All child composables automatically scale
      AppContent()
  }
  ```
- **Typography scale**: Define the app's `Typography` with generous base sizes:
  ```kotlin
  val StrataTypography = Typography(
      displayLarge = TextStyle(fontSize = 48.sp),   // hero titles
      headlineMedium = TextStyle(fontSize = 28.sp),  // section headers
      bodyLarge = TextStyle(fontSize = 20.sp),        // descriptions
      bodyMedium = TextStyle(fontSize = 18.sp),       // metadata
      labelLarge = TextStyle(fontSize = 16.sp),       // badges, chips
  )
  ```
  These are deliberately larger than Material Design defaults, which are designed for phone-distance viewing.
- **High contrast mode**:
  ```kotlin
  val highContrastColors = darkColorScheme(
      onSurface = Color.White,              // pure white text
      surface = Color.Black,                // pure black background
      primary = Color(0xFF64B5F6),          // bright blue for accents
      onPrimary = Color.Black,
  )
  ```
- **System font size**: Compose's `sp` unit already respects the system font scale. No additional code needed beyond using `sp` consistently (never `dp` for text).
- **Fire Stick constraint**: Larger text means fewer items visible per screen. Ensure `TvLazyRow` and `TvLazyColumn` handle variable item sizes gracefully. Test the "Extra Large" setting to verify no layout overflow or truncation issues.
- **Testing**: Test with Fire OS Accessibility > Font Size set to maximum. All screens must remain functional and readable.

## Priority Recommendation

**P1 -- Design principle from day one.** Using `sp` units and generous base sizes costs nothing and must be established in the initial typography system. The in-app size setting and high contrast mode can follow in v2, but the foundation must be correct from the start. Accessibility litigation is increasing in the streaming space; proactive compliance avoids legal risk.
