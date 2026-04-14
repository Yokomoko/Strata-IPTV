# 42 - Multi-Language UI (i18n / l10n for Non-English Users)

## Current State

All user-facing strings are hard-coded directly in Kotlin composables (e.g., `Text(text = "Refresh Library")`, `Text(text = "M3U Playlist")`, `Text(text = "Strata TV")`). There are no Android string resources (`strings.xml`), no localization files, and no mechanism to switch the UI language. The app is English-only. The only string resource reference is `@string/app_name` in the manifest.

## Gap

While the current user base is English-speaking, internationalizing the app now (extracting strings to resources) is significantly easier than retrofitting later when dozens of screens exist. String extraction is also a prerequisite for proper accessibility (screen readers use string resources for announcements) and future distribution to non-English markets.

## User Story

**As a non-English-speaking user**, I want the app UI to be available in my language, so that I can navigate the app comfortably.

## Acceptance Criteria

1. All user-facing strings are extracted to `res/values/strings.xml` using `stringResource(R.string.xxx)`.
2. String keys follow a consistent naming convention: `{screen}_{element}_{description}` (e.g., `settings_playlist_refresh`, `home_rail_continue_watching`).
3. Plurals use Android's `<plurals>` resource where appropriate (e.g., "42 items in library" vs. "1 item in library").
4. Dynamic strings use parameterized resources (`<string name="library_count">%d items in library</string>`).
5. At least one additional locale is provided as a proof of concept (e.g., `res/values-fr/strings.xml` for French).
6. The app respects the system locale set on the Fire Stick.
7. A "Language" option in Settings allows overriding the system locale with an in-app preference (useful if the Fire Stick is set to a different language than the user prefers for this app).
8. Date and time formatting uses locale-aware formatters (`DateTimeFormatter.ofLocalizedDateTime()`).
9. RTL layout support is enabled in the manifest (`android:supportsRtl="true"`) even if no RTL locale is provided yet.
10. No hard-coded strings remain in composable functions after extraction.

## Technical Approach

- **String extraction**: Systematically replace every `"literal string"` in composables with `stringResource(R.string.xxx)`. Use Android Studio's "Extract String Resource" refactoring.
- **Resource files**: Create `res/values/strings.xml` with all English strings. Create `res/values-fr/strings.xml` as a proof-of-concept translation.
- **In-app locale override**: Use `AppCompatDelegate.setApplicationLocales()` (AndroidX) or `context.createConfigurationContext()` to override the system locale. Store the preference in SharedPreferences.
- **Plurals**: Use `pluralStringResource(R.plurals.library_item_count, count, count)` for countable strings.
- **RTL**: Add `android:supportsRtl="true"` to the `<application>` tag in `AndroidManifest.xml`. Use `start`/`end` instead of `left`/`right` in padding/margin (Compose already uses `start`/`end` by default).
- **Testing**: Switch Fire Stick language to verify locale-aware behavior. Use Android Studio's "Translations Editor" to manage strings.

## Priority

**Low** -- The app is currently personal-use with an English-speaking user. However, string extraction should be done sooner rather than later to avoid a painful retrofit. Consider doing just the extraction (AC 1-4) as a standalone task.

## Effort Estimate

**2-3 days**

- Day 1: Extract all strings from all screens to `strings.xml` (mechanical but thorough)
- Day 2: Plurals, parameterized strings, date formatting, RTL support flag
- Day 3: French translation proof-of-concept, in-app locale override, testing
