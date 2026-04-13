package com.strata.tv.domain

/**
 * Helpers for extracting structure from the user's M3U display names.
 *
 * Direct port of `lib/core/utils/title_parser.dart` from the Flutter
 * v1 app.  The provider this app targets uses a consistent
 * `"HD : Title YYYY"` / `"HD : Title S01E02"` format — these regexes
 * are tuned to that.  Non-matching display names fall through to the
 * raw value with the `"HD : "` prefix stripped.
 */
object TitleParser {

    private val moviePattern = Regex("""^HD\s*:\s*(.+?)\s+(\d{4})$""")
    private val episodePattern = Regex("""^HD\s*:\s*(.+?)\s+S(\d{2,})E(\d{2,})$""")
    private val hdPrefix = Regex("""^HD\s*:\s*""")
    private val nonAlnum = Regex("""[^a-z0-9\s]""")
    private val multiSpace = Regex("""\s+""")

    /** Parsed result from a movie display name. */
    data class Movie(val title: String, val year: Int)

    /** Parsed result from an episode display name. */
    data class Episode(val seriesTitle: String, val season: Int, val episode: Int)

    /**
     * Try to parse [displayName] as `"HD : Movie Title 2025"`.
     * Returns null when the display name doesn't match the expected
     * shape.
     */
    fun parseMovie(displayName: String): Movie? {
        val match = moviePattern.matchEntire(displayName.trim()) ?: return null
        val (title, year) = match.destructured
        return Movie(title, year.toInt())
    }

    /**
     * Try to parse [displayName] as `"HD : Show Title S01E02"`.
     * Returns null when the display name doesn't match the expected
     * shape.
     */
    fun parseEpisode(displayName: String): Episode? {
        val match = episodePattern.matchEntire(displayName.trim()) ?: return null
        val (title, season, episode) = match.destructured
        return Episode(title, season.toInt(), episode.toInt())
    }

    /** Strip the `"HD : "` prefix that the provider applies to most VOD titles. */
    fun stripHdPrefix(displayName: String): String =
        displayName.replaceFirst(hdPrefix, "").trim()

    /**
     * Lower-cased, alphanumeric-only, single-spaced version of [title].
     * Used as the key for stable content IDs and for series grouping —
     * any two strings that *humans* would consider the same title
     * collapse to the same normalised form.
     */
    fun normalise(title: String): String =
        title.lowercase()
            .replace(nonAlnum, "")
            .replace(multiSpace, " ")
            .trim()
}
