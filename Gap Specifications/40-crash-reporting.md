# 40 - Crash Reporting (Save Crash Logs, Export for Developer)

## Current State

The app has no crash reporting infrastructure. When the app crashes on the Fire Stick, the stack trace goes to logcat and is lost when the logcat buffer rolls over. There is no `Thread.setDefaultUncaughtExceptionHandler`, no crash log persistence, and no way to retrieve crash information after the fact without being actively connected via ADB at the time of the crash.

The project notes indicate that `debugPrint` reaches logcat in release builds, but `developer.log` is stripped and `Log.*` should be gated on `!kReleaseMode`. This means even intentional logging may be inconsistent in release builds.

## Gap

Without crash reporting, bugs in production (on the Fire Stick) are invisible to the developer. Crashes during playback, enrichment, or EPG parsing are silent data losses. The user's only recourse is "it crashed" with no actionable information. For a sideloaded app without Play Store crash reporting, a local crash log is essential.

## User Story

**As the developer**, I want crash logs to be automatically saved to persistent storage and exportable, so that I can diagnose and fix crashes reported by users.

## Acceptance Criteria

1. A custom `UncaughtExceptionHandler` is installed in `StrataApp.onCreate()` that captures the full stack trace.
2. Crash logs are written to a file in app-internal storage: `files/crash-logs/crash-{timestamp}.txt`.
3. Each crash log includes: timestamp, exception class, message, full stack trace, device model, OS version, app version, available memory, and thread name.
4. The most recent 20 crash logs are retained; older ones are auto-purged.
5. The Diagnostics panel (spec #39) shows a "Crash Logs" section listing recent crashes with date and exception summary.
6. Tapping a crash log entry shows the full stack trace in a scrollable text view.
7. "Export All Crash Logs" writes a combined file to shared storage or clipboard.
8. On app restart after a crash, a subtle toast or banner shows: "The app crashed previously. Tap to view details."
9. Non-fatal exceptions (caught errors in sync/enrichment/EPG) are also optionally logged to the same system.
10. Crash logging works in both debug and release builds.

## Technical Approach

- **UncaughtExceptionHandler**: In `StrataApp.onCreate()`, set `Thread.setDefaultUncaughtExceptionHandler` with a custom handler that writes to file then delegates to the default handler (so the system still shows the crash dialog / restarts).
- **File format**: Plain text with a structured header (device info, timestamp) followed by the stack trace. One file per crash.
- **Storage**: Use `context.filesDir / "crash-logs"`. Auto-purge: on startup, list files sorted by date, delete all but the 20 most recent.
- **Post-crash detection**: Write a `last_crash_timestamp` to SharedPreferences in the handler. On next `onCreate()`, check if it is set and show the banner. Clear after the user dismisses or views.
- **Non-fatal logging**: Create a `CrashReporter.logNonFatal(tag: String, throwable: Throwable)` method that writes to the same directory with a `nonfatal-` prefix.
- **UI integration**: The Diagnostics screen (spec #39) reads the crash log directory and displays entries. This spec is standalone but complements #39.
- **No third-party dependencies**: Avoid Firebase Crashlytics or Sentry -- this is a sideloaded personal app. Local-only crash reporting is sufficient and avoids privacy concerns.

## Priority

**Medium** -- Important for the developer's ability to maintain the app. Becomes critical as the app stabilizes and real crashes need diagnosis.

## Effort Estimate

**1-2 days**

- Day 1: Custom `UncaughtExceptionHandler`, file writing, auto-purge, post-crash detection
- Day 2: Diagnostics UI integration (crash log list, detail view, export), non-fatal logging
