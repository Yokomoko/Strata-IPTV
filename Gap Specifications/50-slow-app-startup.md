# 50 - Slow App Startup Times

## Title

App takes forever to load on Fire Stick -- 10-15 second startup times

## Source

- **Reddit**: r/fireTV (top 3 complaint), r/AndroidTV -- "Netflix takes 15 seconds to load on my Fire Stick." "Disney+ splash screen for 10 seconds before I can do anything."
- **Amazon Appstore reviews**: Slow startup cited across all streaming apps, especially on Fire Stick Lite and 2nd-gen Fire Stick.
- **Tech press**: Multiple benchmarking articles comparing streaming app startup times on Fire Stick vs. other platforms.
- **r/PleX**: Plex on Fire Stick is notoriously slow to start, sometimes 20+ seconds.

## The Problem

Streaming apps on Fire Stick have painfully slow startup times:

1. **10-15 second cold starts** -- from app launch to interactive home screen, users wait through splash screens, loading spinners, and API calls.
2. **Authentication checks** -- apps verify subscription status, sync profiles, and check for updates on every launch, blocking the UI.
3. **Heavy frameworks** -- apps built with React Native or web-based frameworks (Disney+, Plex web) have additional JS engine startup overhead.
4. **Network dependency** -- apps that require server-side data before rendering anything show blank screens on slow connections.
5. **Fire Stick hardware** -- 1-2GB RAM, quad-core ARM processor. Apps designed for phones and tablets don't optimise for this constrained environment.
6. **Multiple splash screens** -- some apps show a platform splash, then an app splash, then a loading screen, then the home screen. Each transition adds perceived latency.

## How StrataTV Could Address It

1. **Sub-3-second startup target** -- set an aggressive cold-start performance budget. Show interactive content within 3 seconds on Fire Stick 4K (baseline device).
2. **Offline-first architecture** -- render the home screen from locally cached data (Room database) immediately. Refresh from network in the background. The user sees content instantly even with no network.
3. **No authentication blocking** -- IPTV apps don't have user accounts or subscription checks. Eliminate this entire startup phase.
4. **Minimal splash screen** -- a single 500ms branded splash (for perceived polish), then immediately into the cached home screen.
5. **Lazy initialisation** -- initialise non-critical services (EPG sync, metadata enrichment, background updates) after the home screen is rendered, not before.
6. **Native Compose for TV** -- no web layer, no JS bridge, no React Native. Pure Kotlin/Compose renders directly to the Android UI toolkit with minimal overhead.

## Feasibility Score

**2** (low-moderate effort) -- The architectural decisions (offline-first, lazy init) need to be made early. Compose for TV's native performance is inherently faster than web-based alternatives. The main effort is in disciplined caching and lazy service initialisation.

## Validity Score

**5** (universally experienced) -- Every Fire Stick user experiences slow app starts daily. It is the #1 hardware complaint about Fire Stick and the #1 reason users reach for a different streaming device.

## Impact Score

**10** (Feasibility 2 x Validity 5 = 10)

## Technical Notes

- **Startup sequence optimisation**:
  ```
  T+0ms:    Application.onCreate() -- init Hilt, init Room (pre-built DB schema)
  T+100ms:  Splash screen composable rendered
  T+200ms:  HomeViewModel loads cached rails from Room (synchronous first-frame query)
  T+500ms:  Splash dismissed, home screen rendered with cached data
  T+500ms+: Background: EPG sync, playlist refresh, metadata enrichment start as coroutines
  ```
- **Room pre-population**: On first launch, show a minimal "Getting your channels..." screen while the initial M3U parse runs. On subsequent launches, Room data is immediately available.
- **Baseline Profile**: Generate and include an Android Baseline Profile (`baseline-prof.txt`) in the APK. This pre-compiles critical startup code paths, reducing JIT compilation time on first launch by 30-50%.
  ```kotlin
  // benchmark module
  @ExperimentalBaselineProfilesApi
  class BaselineProfileGenerator {
      @get:Rule val rule = BaselineProfileRule()
      @Test
      fun startupProfile() {
          rule.collect("com.strata.tv") {
              startActivityAndWait()
          }
      }
  }
  ```
- **R8 optimisation**: Ensure full R8 (ProGuard) shrinking and optimisation is enabled for release builds to minimise DEX size and class loading time.
- **Avoid Jetpack Startup library for non-critical components**: Only use `App Startup` for truly critical initialisers (Room, DataStore). Defer everything else.
- **Fire Stick constraint**: The 1GB Fire Stick Lite may swap aggressively on cold start. Keep the initial working set small -- don't load all channels into memory at startup, only the visible rails.
- **Measurement**: Use `reportFullyDrawn()` in the main Activity to track Time to Full Display (TTFD) in Firebase Performance or custom logcat metrics. Set a CI alert if TTFD exceeds 3 seconds on a Fire Stick 4K benchmark device.

## Priority Recommendation

**P0 -- Architectural requirement from day one.** Startup performance is not something you optimise later; it is an architectural decision. The offline-first caching strategy, lazy initialisation, and Baseline Profile must be established in the initial project setup. A fast cold start is one of the strongest first impressions an app can make, especially on constrained hardware where users are conditioned to expect slowness.
