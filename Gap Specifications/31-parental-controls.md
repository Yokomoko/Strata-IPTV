# 31 - Parental Controls (PIN Lock & Content Rating Filter)

## Current State

The app has no access restriction mechanism. All content -- live channels, movies, and box sets -- is visible and playable immediately on launch. Movie and series entities include a `certification` column (populated by TMDB enrichment) but it is never used for filtering. There is no PIN entry, no profile system, and no age-gate.

## Gap

A household Fire Stick is shared by adults and children. Without parental controls, adult-rated IPTV content (18-rated movies, adult channels) is one D-pad click away from any user. This is a basic expectation for any TV app and a potential blocker for family use.

## User Story

**As a parent**, I want to set a 4-digit PIN that locks adult content behind a gate, so that my children cannot accidentally browse or play age-inappropriate streams.

## Acceptance Criteria

1. A new "Parental Controls" section appears in Settings, behind a PIN challenge if a PIN is already set.
2. First-time setup prompts the user to create a 4-digit PIN using the Fire Stick remote (D-pad number entry or on-screen numpad).
3. User can select a maximum allowed content rating from a predefined list: U, PG, 12, 15, 18 (UK BBFC ratings).
4. Content with a rating above the threshold is hidden from all browse screens (Home rails, Movies grid, Box Sets grid, Search results).
5. Attempting to play a hidden item (e.g. via deep link or direct stream URL) shows a PIN challenge overlay.
6. Correct PIN entry grants a time-limited session (configurable: 1 hour / 2 hours / until app restart) where all content is visible.
7. "Disable Parental Controls" option in Settings requires current PIN.
8. PIN is stored securely (hashed, not plain text) in EncryptedSharedPreferences.
9. After 5 incorrect PIN attempts, entry is locked for 60 seconds.
10. All UI elements are fully navigable with D-pad / Fire Stick remote.

## Technical Approach

- **Storage**: Store the hashed PIN and rating threshold in `EncryptedSharedPreferences` (AndroidX Security library). Add `security-crypto` dependency.
- **Domain**: Create `ParentalGatekeeper` singleton that exposes `isLocked: StateFlow<Boolean>` and `maxRating: StateFlow<String>`. ViewModels query this to filter content.
- **DAO changes**: Add `@Query` variants to `MovieDao` and `SeriesDao` that accept a `maxCertification` parameter, filtering on the existing `certification` column. For channels, add a `rating` column to `ChannelEntity` or rely on group-title heuristics (groups containing "XXX", "Adult").
- **UI**: New `ParentalPinDialog` composable -- a full-screen overlay with a 4-digit numpad optimized for remote navigation. New `ParentalSettingsScreen` composable accessible from Settings.
- **Session**: `ParentalGatekeeper` holds a `unlockExpiry: Instant?` that auto-locks when expired, checked on each navigation event.
- **Fire Stick considerations**: The on-screen numpad must have large touch targets (54dp minimum) and clear focus indicators for D-pad navigation.

## Priority

**High** -- Essential for any TV app used in a household. Blocks family adoption.

## Effort Estimate

**3-4 days**

- Day 1: `ParentalGatekeeper`, EncryptedSharedPreferences setup, PIN hash/verify logic
- Day 2: `ParentalPinDialog` composable, numpad navigation, lockout timer
- Day 3: DAO query variants with certification filter, ViewModel integration across Home/Movies/Shows/Search
- Day 4: Settings UI, session timeout, edge cases (deep links, back-stack), testing
