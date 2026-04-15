# 41 - Accessibility (TalkBack Support, High-Contrast Mode, Font Scaling)

## Current State

The app uses Compose for TV (`tv.material3`) components (`ListItem`, `Text`) which have some baseline accessibility support via Compose's semantics system. However, there are no explicit `contentDescription` annotations on interactive elements, no high-contrast color scheme, and no font scaling controls. The `StrataColors` object defines a fixed dark color palette with no alternatives. The app is designed exclusively for visual navigation with a D-pad remote.

## Gap

Android TV accessibility features (TalkBack for TV, Switch Access) are used by visually impaired users. Without proper semantic annotations, screen readers cannot describe content items, navigation state, or sync progress. Users with low vision may struggle with the fixed color scheme and font sizes on a 10-foot UI. Amazon Fire TV supports TalkBack (called "VoiceView" on Fire OS), and apps are expected to be compatible.

## User Story

**As a visually impaired user**, I want the app to work with Fire TV's VoiceView screen reader and offer high-contrast and larger text options, so that I can navigate and enjoy content independently.

## Acceptance Criteria

1. All interactive elements (ListItem, buttons, cards, poster images) have meaningful `contentDescription` semantics.
2. Poster cards describe: content title, year, rating (e.g., "Breaking Bad, 2008, rated 9.5").
3. Channel items describe: channel name, current programme (if EPG data available), channel number.
4. Sync progress is announced as a live region update (TalkBack reads changes automatically).
5. Navigation state changes are announced (e.g., "Movies screen", "Settings screen").
6. A "High Contrast" toggle in Settings switches to an alternate color palette with WCAG AA-compliant contrast ratios (minimum 4.5:1 for normal text, 3:1 for large text).
7. A "Text Size" setting offers three levels: Standard, Large (+25%), Extra Large (+50%).
8. Font scaling applies to all text in the app (headings, body, subtitles, metadata).
9. Focus indicators are clearly visible in both standard and high-contrast modes (minimum 2dp solid border).
10. The app passes a basic accessibility audit using Android's Accessibility Scanner.

## Technical Approach

- **Semantics**: Add `Modifier.semantics { contentDescription = "..." }` to all interactive composables. For image-based cards (`PosterCard`, `Rail`), use `contentDescription` on the `Image` or `AsyncImage`.
- **Live regions**: Use `Modifier.semantics { liveRegion = LiveRegionMode.Polite }` on sync progress text.
- **High contrast theme**: Create `StrataColorsHighContrast` object with adjusted values. Use a `ThemePreferences` singleton that toggles between `StrataColors` and `StrataColorsHighContrast`. The `Theme.kt` composable reads this preference.
- **Font scaling**: Use a `fontScale: Float` preference (1.0, 1.25, 1.5) applied via `CompositionLocalProvider(LocalDensity provides Density(density, fontScale))` at the root composable.
- **Focus indicators**: Review all `ListItemDefaults.colors()` calls to ensure `focusedContainerColor` provides sufficient contrast against the background in both themes.
- **Testing**: Use Fire TV's VoiceView to manually test navigation flows. Use Android's Accessibility Scanner on an emulator.

## Priority

**Low** -- Important for inclusivity and a polished product, but the primary user base is currently a single household. Priority increases if the app is distributed more widely.

## Effort Estimate

**2-3 days**

- Day 1: Semantic annotations across all screens (Home, Movies, Shows, Live, Settings, Player)
- Day 2: High-contrast color scheme, theme toggle, focus indicator review
- Day 3: Font scaling implementation, VoiceView testing on Fire Stick, accessibility audit fixes
