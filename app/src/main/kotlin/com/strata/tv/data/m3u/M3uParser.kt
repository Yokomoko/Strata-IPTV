package com.strata.tv.data.m3u

import com.strata.tv.domain.ContentType
import com.strata.tv.domain.TitleParser
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.yield

/**
 * Streaming parser for M3U-format IPTV playlists.
 *
 * Direct port of `lib/services/m3u_parser.dart` with the same regex
 * patterns and the same emission contract — batches of [batchSize]
 * entries followed by a final [ParseResult.Complete] when the stream
 * ends.  Behaviour parity is verified by running the v1 fixture suite
 * against this parser in `M3uParserTest`.
 *
 * The parser doesn't do its own I/O — callers feed it the playlist
 * text.  This keeps it trivially testable (string in → entries out)
 * and lets the caller decide whether the playlist comes from the
 * network, a local file, or an asset.
 *
 * Heavy lifting (regex + classification) happens on the calling
 * coroutine.  Use `Dispatchers.Default` if you want the parse to
 * stay off the main thread:
 *
 * ```kotlin
 * val flow = withContext(Dispatchers.Default) {
 *     M3uParser().parse(playlistText)
 * }
 * ```
 */
class M3uParser(private val batchSize: Int = 500) {

    /**
     * Emit batches of parsed entries from [content].
     *
     * Stages:
     * - [ParseResult.Batch] each time we accumulate [batchSize] entries.
     * - [ParseResult.Progress] after each batch with a running tally.
     * - [ParseResult.Complete] once the stream has been consumed.
     *
     * Errors don't terminate the stream — a single bad EXTINF line
     * just gets counted in `skipped` and the next entry is parsed
     * normally.  This matches v1: a broken record in the middle of a
     * 60 000-line playlist shouldn't kill the whole import.
     */
    fun parse(content: String): Flow<ParseResult> = flow {
        val lines = content.lineSequence().iterator()
        val batch = mutableListOf<M3uEntry>()
        var totalParsed = 0
        var totalSkipped = 0

        var currentExtinf: String? = null
        val seenGroupMappings = mutableSetOf<String>()
        // Cooperative yield so a long parse doesn't block whatever
        // coroutine context we're running on (e.g. an IO worker).
        var sinceYield = 0

        while (lines.hasNext()) {
            val line = lines.next().trim()

            when {
                line.startsWith("#EXTINF:") -> {
                    currentExtinf = line
                }

                line.isEmpty() || line.startsWith("#") -> {
                    // Comment / blank — ignore.
                }

                currentExtinf != null && line.startsWith("http") -> {
                    val entry = parseEntry(currentExtinf!!, line)
                    if (entry != null) {
                        // Debug: log first occurrence of each group→type mapping
                        val mapKey = "${entry.groupTitle}→${entry.contentType}"
                        if (mapKey !in seenGroupMappings) {
                            seenGroupMappings.add(mapKey)
                            android.util.Log.w("M3uParser", "[Classify] group='${entry.groupTitle}' → ${entry.contentType} (sample: '${entry.displayName.take(60)}')")
                        }
                        batch.add(entry)
                        totalParsed++
                    } else {
                        totalSkipped++
                    }
                    currentExtinf = null

                    if (batch.size >= batchSize) {
                        emit(ParseResult.Batch(batch.toList()))
                        emit(ParseResult.Progress(totalParsed, totalSkipped))
                        batch.clear()
                    }
                }
            }

            // Cheap cooperative yield every 200 lines.  Keeps the
            // coroutine cancellable and doesn't starve other work.
            if (++sinceYield >= 200) {
                sinceYield = 0
                yield()
            }
        }

        if (batch.isNotEmpty()) {
            emit(ParseResult.Batch(batch.toList()))
        }
        emit(ParseResult.Complete(totalParsed, totalSkipped))
    }

    // -------------------------------------------------------------------------
    // Per-record parsing
    // -------------------------------------------------------------------------

    /** Returns `null` for malformed records — caller treats it as "skipped". */
    private fun parseEntry(extinf: String, url: String): M3uEntry? {
        return try {
            // Display name is whatever follows the LAST comma on the
            // EXTINF line.  Some providers stuff commas inside their
            // tags so `lastIndexOf` is more robust than `split`.
            val lastComma = extinf.lastIndexOf(',')
            val displayName = if (lastComma >= 0) extinf.substring(lastComma + 1).trim() else ""
            if (displayName.isEmpty()) return null

            val tvgId = tvgIdPattern.find(extinf)?.groupValues?.get(1).orEmpty()
            val tvgName = tvgNamePattern.find(extinf)?.groupValues?.get(1).orEmpty()
            val tvgLogo = tvgLogoPattern.find(extinf)?.groupValues?.get(1).orEmpty()
            val groupTitle = groupTitlePattern.find(extinf)?.groupValues?.get(1).orEmpty()
            val tvgType = tvgTypePattern.find(extinf)?.groupValues?.get(1).orEmpty()
            val duration = durationPattern.find(extinf)?.groupValues?.get(1)?.toIntOrNull() ?: 0

            val contentType = classify(tvgType, groupTitle, displayName, url)

            var movieTitle: String? = null
            var movieYear: Int? = null
            var seriesTitle: String? = null
            var seasonNumber: Int? = null
            var episodeNumber: Int? = null

            when (contentType) {
                ContentType.Movie -> {
                    val parsed = TitleParser.parseMovie(displayName)
                    if (parsed != null) {
                        movieTitle = parsed.title
                        movieYear = parsed.year
                    } else {
                        movieTitle = TitleParser.stripHdPrefix(displayName)
                    }
                }
                ContentType.Show -> {
                    val parsed = TitleParser.parseEpisode(displayName)
                    if (parsed != null) {
                        seriesTitle = parsed.seriesTitle
                        seasonNumber = parsed.season
                        episodeNumber = parsed.episode
                    } else {
                        seriesTitle = TitleParser.stripHdPrefix(displayName)
                    }
                }
                ContentType.Live -> { /* nothing extra */ }
            }

            M3uEntry(
                displayName = displayName,
                streamUrl = url,
                groupTitle = groupTitle,
                tvgId = tvgId,
                tvgName = tvgName,
                tvgLogo = tvgLogo,
                tvgType = tvgType,
                extinfDuration = duration,
                contentType = contentType,
                movieTitle = movieTitle,
                movieYear = movieYear,
                seriesTitle = seriesTitle,
                seasonNumber = seasonNumber,
                episodeNumber = episodeNumber,
            )
        } catch (e: Throwable) {
            // Defensive — anything unexpected (regex blew up, weird
            // unicode, …) gets skipped rather than aborting the import.
            null
        }
    }

    /**
     * Classification cascade — same ordering as v1.  More reliable
     * signals win over less reliable ones.
     */
    private fun classify(
        tvgType: String,
        groupTitle: String,
        displayName: String,
        url: String,
    ): ContentType {
        // 1. Explicit `tvg-type` is definitive when present.
        if (tvgType == "movie") return ContentType.Movie
        if (tvgType == "series") return ContentType.Show

        // 2. Episode pattern in the title — MUST run before group-title
        //    keywords because some M3U providers use a single "Movie VOD"
        //    group for ALL VOD content including shows.  A title like
        //    "HD: The Other Bennet Sister S01E01" must be classified as
        //    a Show even when the group says "Movie".
        if (TitleParser.parseEpisode(displayName) != null) return ContentType.Show

        // 3. URL structure (Xtream-style providers).
        if ("/series/" in url) return ContentType.Show
        if ("/movie/" in url) return ContentType.Movie

        // 4. Group-title keywords (the provider's own categorisation).
        val groupLower = groupTitle.lowercase()
        if ("series" in groupLower || "tv vod" in groupLower || "shows" in groupLower) {
            return ContentType.Show
        }
        if ("movie" in groupLower || "film" in groupLower) return ContentType.Movie

        // 5. Movie year pattern fallback.
        if (TitleParser.parseMovie(displayName) != null) return ContentType.Movie

        // 6. Default: live channel.
        return ContentType.Live
    }

    private companion object {
        // Pre-compiled once per parser; matches v1's lazily-cached
        // top-level patterns.
        val tvgIdPattern = Regex("""tvg-id="([^"]*)"""")
        val tvgNamePattern = Regex("""tvg-name="([^"]*)"""")
        val tvgLogoPattern = Regex("""tvg-logo="([^"]*)"""")
        val groupTitlePattern = Regex("""group-title="([^"]*)"""")
        val tvgTypePattern = Regex("""tvg-type="([^"]*)"""")
        val durationPattern = Regex("""#EXTINF:\s*(-?\d+)""")
    }
}

/**
 * Streaming output of [M3uParser.parse].
 *
 * Emitted in this order: zero or more [Batch]/[Progress] pairs, then
 * a final [Complete].  Subscribers may exit early; the parser is
 * cooperative cancellation-aware via the periodic `yield()`s in the
 * main loop.
 */
sealed interface ParseResult {

    /** A batch of parsed entries ready for whatever the caller does
     *  with them (typically: insert into Room in a transaction). */
    data class Batch(val entries: List<M3uEntry>) : ParseResult

    /** Running tally emitted after each [Batch]. */
    data class Progress(val parsed: Int, val skipped: Int) : ParseResult

    /** Final emission after the stream has been fully consumed. */
    data class Complete(val totalParsed: Int, val totalSkipped: Int) : ParseResult
}
