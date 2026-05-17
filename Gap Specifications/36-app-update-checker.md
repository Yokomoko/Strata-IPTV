# 36 - App Update Checker (Check GitHub Releases for New APK)

## Current State

The app is sideloaded via ADB with no update mechanism. The version is hard-coded as `versionName = "0.1.0"` and `versionCode = 1` in `build.gradle.kts`. The Settings screen displays "v0.1.0" as static text. There is no way for the app to detect that a newer version is available, nor any mechanism to notify the user or facilitate the update.

## Gap

Sideloaded apps on Fire Stick have no access to the Amazon Appstore's update infrastructure. Users must manually check for new builds, download the APK via ADB, and re-install. This is friction-heavy and means users often run outdated versions with known bugs or missing features.

## User Story

**As a user**, I want the app to check for new versions on GitHub and notify me when an update is available, so that I can stay current without manually checking.

## Acceptance Criteria

1. On app launch (and optionally on a configurable schedule), the app queries the GitHub Releases API for the latest release tag.
2. The current `versionName` is compared to the latest release tag using semantic versioning.
3. If a newer version is available, a non-intrusive banner appears on the Home screen: "Strata TV vX.Y.Z is available".
4. The banner includes a "View Details" action that navigates to an update details screen showing release notes (from the GitHub release body).
5. Settings screen shows: current version, latest available version, and a "Check for Updates" button.
6. The check respects rate limiting (no more than once per hour, cached result in SharedPreferences).
7. If the network is unavailable, the check silently fails without bothering the user.
8. The update check can be disabled in Settings ("Check for updates: On/Off").
9. Release notes are rendered as plain text (Markdown stripped) for readability on the TV UI.
10. The GitHub repository URL is configurable in `AppConfig` (not hard-coded deep in logic).

## Technical Approach

- **API call**: Use OkHttp to call `https://api.github.com/repos/{owner}/{repo}/releases/latest`. Parse the response JSON with `kotlinx.serialization` to extract `tag_name`, `body`, and `assets[].browser_download_url`.
- **Version comparison**: Parse `tag_name` (e.g., "v0.2.0") and compare against `BuildConfig.VERSION_NAME` using a simple semver comparator.
- **Caching**: Store `lastCheckTime` and `latestVersion` in SharedPreferences. Skip the API call if checked within the last hour.
- **Config**: Add `GITHUB_REPO_OWNER` and `GITHUB_REPO_NAME` to `AppConfig`.
- **UI**: `UpdateBanner` composable shown conditionally on HomeScreen. `UpdateDetailsScreen` for release notes. "Check for Updates" ListItem in Settings.
- **Rate limiting**: GitHub API allows 60 unauthenticated requests/hour. With the 1-hour cache, this is well within limits.
- **No auto-download**: This spec only covers detection and notification. Spec #44 (OTA Sideload Update) covers the actual APK download and install flow.

## Priority

**Medium** -- Important for long-term maintainability but not blocking daily use. Value increases as the app matures and releases become frequent.

## Effort Estimate

**1-2 days**

- Day 1: GitHub API client, version comparator, caching logic, `UpdateCheckRepository`
- Day 2: Home screen banner, Settings integration, update details screen, testing
