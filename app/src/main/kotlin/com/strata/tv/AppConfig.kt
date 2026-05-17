package com.strata.tv

/**
 * Non-secret app configuration.
 *
 * Provider URL and credentials live in
 * [com.strata.tv.data.settings.SettingsRepository] (entered via the
 * first-run wizard, never embedded).  The TMDB API key lives in
 * `BuildConfig.TMDB_API_KEY` and is injected at build time from
 * `local.properties` (dev) or the `TMDB_API_KEY` env var (CI/release
 * pipeline) so it never lands in git history.
 */
object AppConfig {
    /** XMLTV EPG feed. */
    const val EPG_URL: String = "https://epg.iptv.cat/epg.xml"

    /** TMDB image base URL + the sizes we use for poster cards / backdrops. */
    const val TMDB_IMAGE_BASE: String = "https://image.tmdb.org/t/p"
    const val TMDB_POSTER_SIZE: String = "w500"

    /**
     * TMDB backdrop size.  `w1280` is the largest size that has visible
     * benefit on a 1080p Fire Stick display.  `original` would be 3840-wide
     * JPEG decoding to a ~30 MB Bitmap per hero, a real OOM risk on a
     * device with ~512 MB of app heap competing with ExoPlayer + Compose.
     */
    const val TMDB_BACKDROP_SIZE: String = "w1280"

    /**
     * TMDB API key, injected via `BuildConfig`.  Exposed here so the
     * data layer doesn't have to import `BuildConfig` directly.
     */
    val TMDB_API_KEY: String get() = BuildConfig.TMDB_API_KEY
}
