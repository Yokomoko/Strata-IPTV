# 45 - Signed Release Build Pipeline (Keystore, ProGuard, APK Signing)

## Current State

The `build.gradle.kts` has a `release` build type with `isMinifyEnabled = true` and `isShrinkResources = true` using ProGuard, but the release signing config is set to the debug keystore:

```kotlin
signingConfig = signingConfigs.getByName("debug")
```

This means release builds are signed with the auto-generated debug key, which:
- Is unique per development machine (not reproducible)
- Cannot be used for Play Store / Amazon Appstore submission
- Means APKs built on different machines cannot update each other
- Has no security guarantees (the debug keystore password is publicly known: "android")

There is no CI/CD pipeline, no GitHub Actions workflow, no keystore management, and no automated build process.

## Gap

Using the debug keystore for release builds is acceptable during early development but creates multiple problems as the app matures: builds from different machines are incompatible (different debug keys), OTA updates (spec #44) will fail if the signing key changes, and the app cannot be distributed via any app store. A proper signing pipeline is a prerequisite for reliable sideload updates and future distribution.

## User Story

**As the developer**, I want a repeatable, secure build pipeline that produces properly signed release APKs, so that I can distribute consistent updates and eventually publish to the Amazon Appstore.

## Acceptance Criteria

1. A dedicated release keystore is generated and stored securely (not committed to version control).
2. The `build.gradle.kts` `release` block references the release keystore via environment variables or a `local.properties` file.
3. A `signingConfigs` block is defined for `release` with keystore path, key alias, store password, and key password read from environment variables.
4. The build falls back to the debug keystore if release signing credentials are not available (so development builds still work).
5. ProGuard rules (`proguard-rules.pro`) are reviewed and updated for all libraries in use (Room, Retrofit, kotlinx.serialization, Coil, Hilt, Media3).
6. A GitHub Actions workflow file (`.github/workflows/release.yml`) automates: checkout, build, sign, and upload APK as a release artifact.
7. The workflow is triggered on git tag push (e.g., `v*`).
8. Secrets (keystore file, passwords) are stored in GitHub Actions secrets, not in the repository.
9. The release APK is tested for: correct signing, ProGuard compatibility (no runtime crashes from stripped classes), and installability on Fire Stick.
10. Version code and version name are derived from the git tag in CI builds.
11. The APK is named with version: `strata-tv-{versionName}.apk`.

## Technical Approach

- **Keystore generation**: `keytool -genkey -v -keystore strata-release.keystore -alias strata -keyalg RSA -keysize 2048 -validity 10000`. Store the keystore file outside the repo. Back up to a secure location.
- **Gradle signing config**:
  ```kotlin
  signingConfigs {
      create("release") {
          storeFile = file(System.getenv("STRATA_KEYSTORE_PATH") ?: "debug.keystore")
          storePassword = System.getenv("STRATA_KEYSTORE_PASSWORD") ?: "android"
          keyAlias = System.getenv("STRATA_KEY_ALIAS") ?: "androiddebugkey"
          keyPassword = System.getenv("STRATA_KEY_PASSWORD") ?: "android"
      }
  }
  ```
- **ProGuard rules**: Add keep rules for:
  - `kotlinx.serialization` (`@Serializable` classes)
  - Retrofit service interfaces
  - Room entities and DAOs (KSP-generated code)
  - Hilt components
  - Media3 ExoPlayer (already handled by consumer ProGuard rules, but verify)
- **GitHub Actions**: Create a workflow that decodes a base64-encoded keystore from secrets, writes it to a temp file, sets environment variables, runs `./gradlew assembleRelease`, and uploads the APK artifact. Optionally create a GitHub Release with the APK attached.
- **Version from tag**: Use a Gradle plugin or script to parse `git describe --tags` and set `versionName` and `versionCode` dynamically.
- **APK naming**: Use `applicationVariants.all { outputFileName = "strata-tv-${versionName}.apk" }` in the `android` block.

## Priority

**High** -- Prerequisite for reliable OTA updates (spec #44) and any form of distribution. The current debug-key signing is a ticking time bomb -- if the development machine changes, existing installs cannot be updated.

## Effort Estimate

**1-2 days**

- Day 1: Keystore generation, Gradle signing config with env vars, ProGuard rule review and testing
- Day 2: GitHub Actions workflow, version-from-tag automation, APK naming, end-to-end test (build on CI, install on Fire Stick)
