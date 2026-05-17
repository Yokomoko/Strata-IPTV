# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What is Strata TV

An Android TV / Fire Stick IPTV app written in Kotlin with Compose for TV. It is a native rewrite of a Flutter v1 app (`Yokomoko/Strata`). The app parses M3U playlists and XMLTV EPG feeds, classifies content into live channels / movies / shows, enriches metadata from TMDB, and presents a Sky-style 10-foot UI with D-pad navigation.

## Build & Run

Requires JDK 17, Android SDK 34, Gradle 8.10.2.

```bash
# Debug build
./gradlew :app:assembleDebug

# Release build (uses debug signing for sideloads)
./gradlew :app:assembleRelease

# Install to connected Fire Stick / emulator
adb install -r app/build/outputs/apk/release/app-release.apk

# Run unit tests (JVM-only, no Android runtime)
./gradlew :app:test

# Run a single test class
./gradlew :app:test --tests "com.strata.tv.domain.TitleParserTest"
```

## Project Structure

Single `:app` module, all source under `app/src/main/kotlin/com/strata/tv/`.

### Layer breakdown

- **`domain/`** — Pure Kotlin, no Android imports. Content classification (`ContentType`), title parsing (`TitleParser`), channel deduplication (`ChannelDeduplicator`), fuzzy matching (`FuzzyMatch`), genre grouping (`GenreGrouper`), Sky channel numbering (`SkyChannelNumbers`), content ID hashing (`ContentIdHasher`). These are JVM-testable by design.

- **`data/m3u/`** — Streaming M3U parser. Emits `Flow<ParseResult>` in batches (default 500). Accepts `Sequence<String>` for line-by-line streaming from OkHttp without loading the full playlist into memory. Classification cascade: `tvg-type` > episode pattern in title > URL path (`/series/`, `/movie/`) > group-title keywords > movie year pattern > default to Live.

- **`data/xmltv/`** — Streaming XMLTV EPG parser. Processes ~150 MB EPG feeds via `InputStream` without full materialisation.

- **`data/tmdb/`** — TMDB enrichment. `TmdbApi` (Retrofit), `MovieEnrichmentService` / `SeriesEnrichmentService` enrich movies and series with posters, backdrops, ratings, cast, trailers. `EnrichmentProgressTracker` is a singleton `StateFlow` driving the sidebar progress ring across sync and enrichment phases.

- **`data/epg/`** — `EpgChannelMatcher` bridges XMLTV channel IDs to M3U entries via fuzzy matching on display names and tvg-id.

- **`data/db/`** — Room database (`strata-tv.db`, schema version 4). Entities: `sources`, `content_items`, `channels`, `movies`, `series`, `episodes`, `programmes`, `continue_watching`, `watch_history`, `favourites`, `watchlist`. One DAO per table family, each injected separately via Hilt. WAL mode for concurrent reads during large EPG writes.

- **`data/repo/`** — `SyncService` orchestrates HTTP → M3U parse → classify → dedup → Room upsert. `SyncWorker` runs periodic sync (12h) via WorkManager. `EpgFetchService` handles XMLTV fetch with freshness gating (skip if data >2h ahead). `BootstrapRepository` ensures a source row exists.

- **`di/`** — `AppModule` provides Room, OkHttp (shared across IPTV + TMDB), M3uParser, all DAOs. `TmdbModule` provides Retrofit-backed `TmdbApi`.

- **`ui/`** — Compose for TV screens. `Shell` is the top-level composable: sidebar + content area + splash overlay + detail overlay + player overlay. Navigation is a custom `AppNavState` (not Jetpack Navigation) using `mutableStateOf` — destinations are an enum in `Destination.kt`. Detail and player screens are overlay layers with `return`-based z-ordering (player > detail > shell).

### Screen destinations

Home, Live (TV Guide), Movies, Shows (Box Sets), Search, Settings — all wired in `Shell.kt` via `when(nav.current)`.

## Key Architecture Decisions

- **No Jetpack Navigation** — custom `AppNavState` with `FocusRequester`-based D-pad navigation. Detail/player screens are overlays, not nav graph destinations.
- **Streaming parsers** — M3U and XMLTV parse from streams/sequences, not materialised strings. Critical for Fire Stick's ~512 MB app heap.
- **Room projections** — `MovieListItem` is a lightweight projection that omits heavy text columns (`overview`, `cast`, `backdrop_url`) to avoid CursorWindow overflow with 2000+ movies.
- **`contentId` stability** — `ContentIdHasher` produces deterministic IDs from (sourceKey, normalisedTitle, groupTitle, streamUrl) so user data (favourites, watchlist, continue-watching) survives re-sync.
- **Channel deduplication** — `ChannelDeduplicator` collapses HEVC/FHD/UKHD variants to one per logical channel, keeping the best quality.
- **Enrichment is reactive** — Rails on Home/Movies/Shows screens observe Room `Flow` queries. Enrichment services write to Room and the UI rebuilds automatically via Flow collection.

## Testing

Unit tests live in `app/src/test/` and run on the JVM (no Android emulator). They cover domain logic and parsers. The test framework is JUnit 4 + Google Truth assertions + kotlinx-coroutines-test.

```bash
# All tests
./gradlew :app:test

# Single test
./gradlew :app:test --tests "com.strata.tv.domain.ChannelDeduplicatorTest"
```

## DI Pattern

Hilt with `@AndroidEntryPoint` on `MainActivity`, `@HiltWorker` on `SyncWorker`. ViewModels use `@HiltViewModel`. DAOs are provided individually (not the whole `AppDatabase`) so each ViewModel only depends on the queries it needs. OkHttpClient is a shared singleton — IPTV and TMDB calls share connection pooling.

## Fire Stick Specifics

- `minSdk 21` covers all Fire Stick devices; `abiFilters` restricted to `armeabi-v7a` + `arm64-v8a`.
- `MainActivity.dispatchKeyEvent` intercepts `KEYCODE_SEARCH`/`KEYCODE_VOICE_ASSIST`/`KEYCODE_ASSIST` to prevent Fire OS from stealing focus.
- TMDB backdrop size capped at `w1280` — `original` would OOM the ~512 MB heap.
- Release builds use R8 minification + resource shrinking. ProGuard rules keep kotlinx.serialization, Hilt, and Room entity classes.

## Version Catalog

All dependency versions live in `gradle/libs.versions.toml`. Key versions: Kotlin 2.0.21, Compose BOM 2024.10.01, TV Foundation 1.0.0-alpha11, Hilt 2.52, Room 2.6.1, Media3 1.4.1, Coil 3.0.4.
