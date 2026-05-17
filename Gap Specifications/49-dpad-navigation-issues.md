# 49 - Inconsistent D-Pad Navigation and Focus Issues on TV

## Title

Broken D-pad navigation: lost focus, unreachable buttons, random jumps

## Source

- **Reddit**: r/fireTV, r/AndroidTV -- the single most common technical complaint about streaming apps on TV platforms. "I press down and focus jumps to a random place." "Can't reach the search button with the remote."
- **Amazon Appstore reviews**: D-pad issues appear in reviews for every major app. Disney+ and Apple TV+ are particularly criticised for poor TV navigation.
- **Developer forums**: Android TV/Compose for TV developers frequently discuss focus management as the hardest UX problem to solve.
- **Accessibility**: Users who cannot use touch (the entire TV audience) are 100% dependent on D-pad navigation working correctly.

## The Problem

D-pad (directional pad) navigation on streaming TV apps is frequently broken:

1. **Focus disappears** -- after closing a dialog, returning from playback, or scrolling, the focus indicator vanishes. The user presses buttons and nothing happens until they randomly mash keys to regain focus.
2. **Non-linear focus movement** -- pressing "Down" doesn't go to the visually-below element. Focus jumps to a distant element, often on a different part of the screen.
3. **Unreachable elements** -- some buttons or menu items cannot be reached via D-pad at all, only via touch (which is impossible on Fire Stick with the standard remote).
4. **Focus traps** -- focus enters a component (e.g., a horizontal carousel) and cannot exit. The user presses Up/Down repeatedly with no effect.
5. **Scroll position lost** -- navigating to a detail screen and pressing Back returns to the top of the browse screen instead of the previously focused item.
6. **Inconsistent focus indicators** -- some apps don't show a clear highlight on the focused element, making it impossible to know where you are.

These issues are especially severe on Fire Stick because the remote ONLY has a D-pad -- there is no touch fallback.

## How StrataTV Could Address It

1. **First-class D-pad design** -- since StrataTV is Fire Stick-only, D-pad navigation is not an afterthought; it is THE input method. Every screen must be designed D-pad-first.
2. **Visible focus indicators** -- every focusable element has a clear, consistent highlight (border glow, scale-up, colour change). Use Compose for TV's `Border` and `Glow` focus effects.
3. **Predictable focus movement** -- focus moves to the spatially nearest element in the pressed direction. No long-distance jumps. Use `focusRestorer()` and explicit `FocusRequester` chains.
4. **Focus restoration on Back navigation** -- when returning to a screen, focus is restored to the previously focused item at the correct scroll position.
5. **No focus traps** -- every container has clear entry/exit focus paths. Horizontal carousels allow Up/Down to exit to the rail above/below.
6. **Focus testing protocol** -- every screen is tested with the D-pad through all navigation paths before release. No screen ships without a complete focus flow verification.

## Feasibility Score

**3** (moderate effort) -- Good D-pad navigation requires disciplined focus management across every screen. Compose for TV provides good primitives (`FocusRequester`, `focusRestorer()`, `onFocusChanged`) but they must be applied correctly and consistently. This is ongoing discipline, not a one-time feature.

## Validity Score

**5** (universally experienced) -- Every TV app user encounters focus issues. It is the most fundamental UX requirement for a TV app and the most common cause of user frustration. On Fire Stick with D-pad-only input, broken focus is a complete UX failure.

## Impact Score

**15** (Feasibility 3 x Validity 5 = 15)

## Technical Notes

- **Compose for TV focus system**: Use `Modifier.focusRequester()`, `Modifier.focusRestorer()`, `Modifier.onFocusChanged {}`, and `FocusManager.moveFocus()` consistently.
- **Focus restoration pattern**:
  ```kotlin
  // In ViewModel, persist the last focused index
  var lastFocusedIndex by mutableIntStateOf(0)
  
  // In Composable, restore on composition
  val focusRequester = remember { FocusRequester() }
  LaunchedEffect(Unit) {
      focusRequester.requestFocus()
  }
  ```
- **TvLazyColumn/TvLazyRow**: These Compose for TV components handle focus-driven scrolling natively, but require `pivotOffsets` tuning to keep focused items visible.
- **Focus exit from carousels**: Use `focusProperties { exit = { ... } }` to explicitly define where focus goes when leaving a `TvLazyRow` in the Up or Down direction.
- **Back navigation focus**: Use a `FocusRestorationState` object saved in the `NavBackStackEntry` arguments to restore the exact item index and scroll offset.
- **Focus testing**: Create a simple focus-logging utility that logs `onFocusChanged` events with the composable tag, enabling systematic verification of focus flows during QA.
- **Fire Stick constraint**: Focus rendering (border glow, scale animation) must be lightweight. Avoid complex shadow/blur effects that cause frame drops on Fire Stick Lite. Prefer `Modifier.border()` with a solid colour over `Modifier.glow()` with blur radius.
- **Debug overlay**: A development-only toggle that highlights all focusable elements with coloured borders, making it easy to spot unreachable elements during testing.

## Priority Recommendation

**P0 -- Foundational requirement.** This is not a feature; it is the baseline requirement for a TV app to be usable. D-pad navigation quality must be a gating criterion for every screen before it ships. Invest in a focus management utility layer and testing protocol from day one. Poor D-pad navigation is the fastest way to earn 1-star reviews on Fire TV.
