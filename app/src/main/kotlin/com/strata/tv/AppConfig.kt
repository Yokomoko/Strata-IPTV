package com.strata.tv

/**
 * Hard-coded configuration ported from v1's `core/config/app_config.dart`.
 *
 * This is a personal app — there's no point in plumbing these through a
 * settings screen yet.  When/if Strata TV ever ships to a third party
 * we'd move them into a Sources row and let the user paste their own.
 */
object AppConfig {
    /** The user's IPTV playlist URL. */
    const val PLAYLIST_URL: String =
        "https://torrentday.com/iptv/2924524/rbjrxi9iFQ/Default"

    /** XMLTV EPG feed. */
    const val EPG_URL: String = "https://epg.iptv.cat/epg.xml"

    /** TMDB v3 API key. */
    const val TMDB_API_KEY: String = "470b957a64d89a73eadeee858ea390c4"

    /** TMDB image base URL + the sizes we use for poster cards / backdrops. */
    const val TMDB_IMAGE_BASE: String = "https://image.tmdb.org/t/p"
    const val TMDB_POSTER_SIZE: String = "w500"

    /**
     * TMDB backdrop size — `w1280` is the largest size that has visible
     * benefit on a 1080p Fire Stick display.  `original` would be 3840-wide
     * JPEG decoding to a ~30 MB Bitmap per hero — a real OOM risk on a
     * device with ~512 MB of app heap competing with ExoPlayer + Compose.
     */
    const val TMDB_BACKDROP_SIZE: String = "w1280"
}
