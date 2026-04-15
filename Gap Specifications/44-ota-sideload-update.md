# 44 - OTA Sideload Update (Download APK from URL, Self-Update)

## Current State

The app is installed and updated exclusively via ADB commands (`adb install -r strata-tv.apk`). This requires a computer, ADB knowledge, and physical or network access to the Fire Stick. The release build is signed with the debug keystore (`signingConfig = signingConfigs.getByName("debug")` in `build.gradle.kts`). There is no in-app download or install mechanism.

## Gap

Updating a sideloaded app on a Fire Stick is a multi-step process requiring ADB. This is acceptable for development but unsuitable for any user who does not have a development setup. Even the developer must locate the APK file, connect ADB, and run the install command. An OTA update flow would allow one-click updates from the couch.

## User Story

**As a user**, I want to download and install app updates directly from the Fire Stick without needing a computer, so that updating is as simple as pressing a button on my remote.

## Acceptance Criteria

1. When a new version is detected (see spec #36), an "Update Now" button is available.
2. Pressing "Update Now" downloads the APK from the GitHub release asset URL to local storage.
3. Download progress is shown with a progress bar (percentage and MB downloaded / total).
4. Download can be cancelled.
5. After download completes, the app triggers the Android package installer intent to install the APK.
6. The user sees the standard Android "Install unknown apps" permission prompt if not already granted.
7. If the permission is not granted, the app guides the user to the Fire OS settings to enable it.
8. After installation, the app restarts automatically (or the user is prompted to restart).
9. The downloaded APK is cleaned up after successful installation.
10. If the download fails (network error, insufficient storage), a clear error message is shown with a "Retry" option.
11. The APK is verified before installation (file size matches the GitHub asset, or SHA256 checksum if provided in the release).

## Technical Approach

- **Download**: Use OkHttp to download the APK file from the `browser_download_url` in the GitHub release asset. Write to `context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)` or `cacheDir`.
- **Progress**: Use OkHttp's `ResponseBody` with a custom `Source` wrapper that reports bytes read to a `StateFlow<DownloadProgress>`.
- **Install intent**: Use `ACTION_INSTALL_PACKAGE` intent with a `FileProvider` URI:
  ```kotlin
  val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
      data = FileProvider.getUriForFile(context, "${packageName}.provider", apkFile)
      flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
  }
  context.startActivity(intent)
  ```
- **FileProvider**: Add a `<provider>` entry in `AndroidManifest.xml` with `file_provider_paths.xml`.
- **Permission**: Check `Settings.canDrawOverlays()` or `packageManager.canRequestPackageInstalls()` (API 26+). If not granted, launch `Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES` intent.
- **Fire OS specifics**: Fire OS requires "Apps from Unknown Sources" to be enabled in Settings > My Fire TV > Developer Options. The app should detect this and show instructions.
- **Signing**: The update APK must be signed with the same key as the installed APK. This spec depends on spec #45 (Signed Release Build Pipeline) for production use.
- **Cleanup**: Delete the downloaded APK on next app launch after a successful update (detect by comparing `BuildConfig.VERSION_NAME` against a stored "pending update" version).

## Priority

**Medium** -- Significantly improves the update experience. Depends on spec #36 (update checker) for detection and spec #45 (signing) for reliable upgrades.

## Effort Estimate

**2-3 days**

- Day 1: APK download with progress tracking, file storage, error handling
- Day 2: FileProvider setup, install intent, permission handling, Fire OS guidance
- Day 3: Post-install cleanup, verification (size/checksum), restart flow, testing on Fire Stick
