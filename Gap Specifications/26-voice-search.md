# 26 - Voice Search Integration (Alexa)

## Current State

The search screen (`SearchScreen.kt`) uses a `BasicTextField` with software keyboard input. The `SearchViewModel` performs a debounced (250ms) text search against `content_items` using word-split LIKE queries and fuzzy scoring. There is no voice input integration. On Fire Stick, the user must navigate to the search screen, open the on-screen keyboard, and type character by character using the D-pad -- a painful experience that typically takes 30+ seconds to type a movie title. The app does not register any `IntentFilter` for `SEARCH_INTENT` or `MediaStore` search actions, so pressing the Alexa voice button on the Fire Stick remote and saying a movie title has no effect within Strata.

## Gap

Amazon Fire TV's primary search mechanism is Alexa voice. Netflix, Disney+, and Prime Video all register as search-intent receivers, meaning a user can say "Alexa, find The Matrix" and see results within those apps. Fire TV also provides a global search integration via the Catalog Integration API. Strata ignores this entirely, forcing users through the worst input method available on a TV device.

## User Story

As a Fire Stick user, I want to press the voice button on my remote and say a movie or show name so that Strata immediately shows matching results, without me having to type on the on-screen keyboard.

## Acceptance Criteria

1. Pressing the microphone button on the Fire Stick remote while Strata is the foreground app triggers voice input.
2. The recognized speech text is inserted into the search field on the Search screen, and results appear automatically.
3. If the user is not on the Search screen when voice is triggered, the app navigates to Search and populates the query.
4. Voice search works for movies, shows, and live channels (same scope as text search).
5. The feature degrades gracefully on non-Fire-TV devices (voice button simply does nothing or opens the keyboard).
6. Latency from voice button press to results displayed is under 2 seconds (network-dependent for speech recognition, which Amazon handles).

## Technical Approach

1. **AndroidManifest intent filter**: Register the main `Activity` (or a transparent trampoline activity) to handle:
   ```xml
   <intent-filter>
       <action android:name="android.intent.action.SEARCH" />
   </intent-filter>
   <meta-data android:name="android.app.searchable"
              android:resource="@xml/searchable" />
   ```
   Also handle `android.intent.action.ASSIST` and `com.amazon.device.SEARCH_INTENT` for Fire TV specific intents.

2. **searchable.xml**: Define a minimal searchable configuration:
   ```xml
   <searchable xmlns:android="http://schemas.android.com/apk/res/android"
       android:label="@string/app_name"
       android:hint="Search Strata" />
   ```

3. **MainActivity**: In `onCreate` and `onNewIntent`, check for `ACTION_SEARCH` intent and extract `SearchManager.QUERY`. Pass the query string to the navigation state:
   ```kotlin
   val voiceQuery = intent?.getStringExtra(SearchManager.QUERY)
   if (!voiceQuery.isNullOrBlank()) {
       navState.navigate(Destination.Search)
       searchViewModel.onQueryChanged(voiceQuery)
   }
   ```

4. **In-app voice trigger**: For in-app voice input (microphone button pressed while on the search screen), use Android's `SpeechRecognizer` API as a fallback for non-Fire-TV devices.

5. **Fire TV Catalog Integration (optional, Phase 2)**: Register content with Amazon's catalog so that Alexa global search ("Alexa, find action movies") surfaces Strata results even from the Fire TV home screen. This requires a content catalog provider and is a larger effort.

6. **Testing**: Use `adb shell am start -a android.intent.action.SEARCH --es query "Breaking Bad"` to simulate voice search.

## Priority

**P1 - High**. Voice search is the primary input method on Fire Stick. The current keyboard-only search is the single biggest UX friction point on the platform. The basic intent-filter integration is trivial to implement.

## Effort Estimate

**Small (1-2 days)** for basic intent-filter voice-to-search. **Medium (3-5 days)** if adding the full Fire TV Catalog Integration for global Alexa search.
