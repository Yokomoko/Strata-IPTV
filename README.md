# Strata TV

An Android TV / Fire Stick IPTV app that doesn't look like it was built in 2012.

## Why I built this

I've been using IPTV for years and every Android TV app I've tried feels bodged together. SmartersIPTV, XCIPTV, IBO Player, OttPlayer, TiviMate. They all do the job, sort of, but they look stuck in the early 2010s. Tiny text, cramped grids, no posters, no plot summaries unless you tap into menus, and the UI feels like an FTP client with a remote control plugged in.

Meanwhile Netflix, Disney+, Prime Video and Apple TV all run on the same Fire Stick hardware and look great. Big artwork. Smooth animations. Continue Watching that actually makes sense. Episode lists that look like a TV show should look in 2025, not a directory listing.

So I started building the thing I wanted. Strata is opinionated. It tries to feel like Sky Glass or Apple TV rather than a file browser. It pulls posters and backdrops from TMDB, deduplicates the four "HD / FHD / HEVC / UKHD" copies of every channel and movie that providers ship by default, groups TV shows so Continue Watching shows the series rather than the specific episode, and treats your remote like a remote rather than a keyboard.

It also stays out of your way about credentials. Your provider URL, username and password are entered once on first launch and stored locally on the device. Nothing is hard-coded anywhere in this repo.

## What you get

* **Provider setup wizard** with a built-in entry for MyBunny.TV plus generic Xtream Codes and raw M3U options. Subscription expiry pulled from the provider where available.
* **Region and language filters** picked during onboarding so the first sync is already focused on UK and English content (or whatever you want).
* **Sky Glass style home** with hero rotation, Continue Watching, Watchlist, New Episodes and genre rails.
* **TMDB enrichment** for movies and series. Posters, backdrops, plots, cast, ratings, trailers.
* **Smart Continue Watching** that collapses TV show episodes into one entry per series, remembers what you were halfway through, and offers the next episode when you're done.
* **TV Guide** for live channels with proper XMLTV EPG support.
* **Favourite channel zapping** during live TV with D-pad up and down.
* **Auto-play next episode** with a 10 second countdown, just like the streaming services.
* **Watchlist** that survives re-syncs because content IDs are stable hashes, not row IDs.
* **Background sync** via WorkManager so the library stays fresh without slowing down app launch.

## A note on provider URLs

Some IPTV providers ship their own player app (often a rebranded XCIPTV called something like "SkyGlass") that hides the underlying Xtream Codes URL from you. The app just asks for a username and password. Strata isn't doing anything that wrappy app isn't doing, it just makes you aware of the URL.

If you don't know your provider's Xtream host, the easiest way to find it is to ask their support. A polite "what's the get.php URL for my account so I can use a different player?" usually does it. Once you have the host, pick "Custom Xtream" in the wizard, paste the host, type your username and password, and Strata takes care of the rest.

If your provider is MyBunny.TV, just pick it from the wizard and enter your username and password.

## Install

### From a release

1. Grab the latest `strata-tv-<version>.apk` from the [Releases page](../../releases).
2. Sideload it onto your Fire Stick or Android TV. The easiest way on Fire Stick is to install the Downloader app from the Amazon Appstore, then point it at the release asset URL.
3. Launch Strata and follow the wizard.

### A note for Fire Stick users about the apps tile

Amazon's stock Fire TV launcher squares off sideloaded apps' banners, so Strata's wide STRATA wordmark gets cropped down to a centred square in the home row. There's nothing the app can do about this from inside the APK. Two ways round it:

1. **Banner proxy via ATV Launcher** (no launcher replacement needed): use [ATV Launcher's banner generator](https://atvlauncher.trekgonewild.de/) to build a tiny proxy APK that carries the wide banner and forwards to Strata when launched. Install the proxy alongside Strata, launch it once, and Fire TV will use the proxy's banner for the home-row tile.
2. **Replace the launcher entirely**: install ATV Launcher itself and set it as your default home. It honours the standard Android TV banner convention so every sideloaded app renders properly.

### Build from source

You'll need JDK 17 and the Android SDK (API 34).

```bash
git clone https://github.com/Yokomoko/StrataTV.git
cd StrataTV
```

Add a free TMDB API key to `local.properties` (get one from [themoviedb.org](https://www.themoviedb.org/settings/api)):

```
tmdb.api.key=your_key_here
```

Build:

```bash
./gradlew :app:assembleRelease
```

The APK ends up at `app/build/outputs/apk/release/app-release.apk`.

Install via adb:

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

The release build uses debug signing so you can sideload it without setting up a keystore. If you want to ship a signed release for the Amazon Appstore or Google Play, swap in your own signing config in `app/build.gradle.kts`.

## Configuration

Strata supports two ways to connect to your IPTV provider:

1. **Xtream Codes** (recommended): enter host, username and password. Strata uses the standard `get.php` endpoint for the playlist and `player_api.php` for subscription info. MyBunny.TV is preset, any other Xtream provider works under "Custom Xtream".
2. **Raw M3U URL**: paste a URL that already has credentials baked in. Subscription info isn't available because there's no API to query.

You can change provider at any time from Settings without losing your watchlist, continue watching state or favourites. Those are keyed off stable content hashes derived from the title, group and stream URL, so the same content keeps the same ID across re-syncs.

## Filters

The first-run wizard asks for region and language preferences. By default Strata keeps UK and English content and drops the rest. You can change the filters at any time from Settings. The three filter dimensions are:

* **Region**: country prefix matched against the M3U `group-title` (e.g. "UK | Entertainment").
* **Categories**: keyword exclusions for genre groups. Default excludes adult and religious categories. Tick or untick each one.
* **Languages**: TMDB `original_language` whitelist applied to movies and series during enrichment. Default is English.

Filters apply on the next sync. Use the "Refresh library now" button in Settings to apply changes immediately.

## Tests

Unit tests run on the JVM in seconds:

```bash
./gradlew :app:testDebugUnitTest
```

Instrumented tests run on a connected device or emulator:

```bash
./gradlew :app:connectedDebugAndroidTest
```

The instrumented suite covers the setup wizard ViewModel, Room DAO behaviour (Continue Watching aggregation, new-episode detection), and the sync exclusion filter end-to-end against a MockWebServer that stands in for the IPTV provider's `player_api.php` and `get.php` endpoints.

## Tech stack

* Kotlin 2.0 with Jetpack Compose for TV
* Hilt for DI
* Room for local storage
* Media3 ExoPlayer for playback
* Retrofit and OkHttp for TMDB and Xtream API calls
* WorkManager for background sync
* DataStore Preferences for settings and credentials
* Coil 3 for image loading
* `minSdk 21`, `targetSdk 34`, runs on every Android TV, Fire Stick and Android 5.0+ device

## Status

This is a personal project I use every day on my own Fire Stick. It's good enough that I want other people to be able to use it, but it isn't a polished commercial product. Expect rough edges. Bug reports and PRs welcome.

## Support

If Strata is useful to you and you want to help me keep working on it, [GitHub Sponsors](https://github.com/sponsors/Yokomoko) is the easiest way. Even a few quid a month keeps me motivated to ship features rather than just fix my own bugs.

## License

MIT, see [LICENSE](LICENSE).
