package com.strata.tv.domain

/**
 * Deduplicate the user's M3U live-channel list down to one entry per
 * "logical" channel, keeping the highest available quality variant.
 *
 * Direct port of `lib/services/channel_deduplicator.dart` from Strata
 * v1 — that algorithm is well-tested (61 cases in v1's
 * `channel_deduplicator_test.dart`), so we translate idiomatically
 * to Kotlin without changing behaviour.  The same test fixtures will
 * be run against this port in `app/src/test`.
 *
 * The algorithm:
 *   1. For each channel, parse off the quality/region prefix and
 *      compute a normalised name.
 *   2. Group all variants of the same logical channel by the
 *      normalised name.
 *   3. Keep the highest [Quality] in each group as the winner.
 *   4. If the winner's `tvg-id` is empty (HEVC FHD variants often
 *      lack it), inherit a non-empty `tvg-id` from a lower-quality
 *      sibling so EPG matching still works.
 */
object ChannelDeduplicator {

    /**
     * Quality tier ordering; higher ordinal beats lower.  Names match
     * v1's `_Quality` enum so cross-referencing the Dart source is
     * trivial.
     */
    enum class Quality {
        Sd,
        Standard,
        Hd,
        Fhd,
        HevcHd,
        Hevc;
    }

    /**
     * Anything matched by [shouldHide] is dropped before deduplication.
     * Currently only the London Live junk feeds.
     */
    fun shouldHide(displayName: String): Boolean {
        val lower = displayName.lowercase()
        return lower.contains("london live")
    }

    /**
     * Cleaned, human-display version of a raw M3U display name.
     * Strips quality prefixes ("HEVC FHD", "UKHD"), trailing tags
     * (`[VIP]`, `(EN)`, `60FPS`, `HEVC HD`).
     */
    fun cleanChannelName(raw: String): String {
        var name = stripAllPrefixes(raw.trim())
        name = name.replace(Regex("""\s*\[vip\]""", RegexOption.IGNORE_CASE), "")
        name = name.replace(Regex("""\s*\(EN\)""", RegexOption.IGNORE_CASE), "")
        name = name.replace(Regex("""\s*\(S\)""", RegexOption.IGNORE_CASE), "")
        name = name.replaceFirst(
            Regex("""(?:\s+(?:HD|SD|FHD|UHD|HEVC))+\s*$""", RegexOption.IGNORE_CASE),
            "",
        )
        name = name.replaceFirst(
            Regex("""\s+\d+FPS\s*$""", RegexOption.IGNORE_CASE),
            "",
        )
        return name.trim()
    }

    /**
     * Reduce [channels] to one entry per logical channel.
     *
     * The contract is intentionally generic: callers pass in their own
     * channel records and tell us how to read / write the
     * [displayName] and `tvg-id`. That keeps the algorithm
     * independent of any particular database or model class — it can
     * be unit tested with simple data classes and reused by the
     * Room-backed sync service equally.
     *
     * @param tvgId        Reads the channel's `tvg-id`.
     * @param withTvgId    Returns a copy of the channel with the
     *                     supplied `tvg-id` substituted in.  Used to
     *                     patch winners that have an empty `tvg-id`.
     */
    fun <T> dedupe(
        channels: List<T>,
        displayName: (T) -> String,
        tvgId: (T) -> String,
        withTvgId: (T, String) -> T,
    ): List<T> {
        // Best-quality variant per normalised name.
        val best = mutableMapOf<String, Ranked<T>>()
        // Best non-empty tvg-id per normalised name (for inheritance).
        val bestTvgId = mutableMapOf<String, String>()

        for (ch in channels) {
            val raw = displayName(ch)
            if (shouldHide(raw)) continue

            val parsed = parse(raw)
            val id = tvgId(ch)
            if (id.isNotEmpty()) {
                bestTvgId.putIfAbsent(parsed.name, id)
            }

            val existing = best[parsed.name]
            if (existing == null || parsed.quality.ordinal > existing.quality.ordinal) {
                best[parsed.name] = Ranked(ch, parsed.quality)
            }
        }

        return best.entries.map { (key, ranked) ->
            val ch = ranked.channel
            val knownTvgId = bestTvgId[key]
            if (knownTvgId != null && tvgId(ch).isEmpty()) {
                withTvgId(ch, knownTvgId)
            } else {
                ch
            }
        }
    }

    // -------------------------------------------------------------------------
    // Internals: prefix patterns + the normalisation pipeline.
    // -------------------------------------------------------------------------

    /** Pair of `(prefix-regex, quality-tier-it-implies)`. */
    private val prefixPatterns = listOf(
        Regex("""^HEVC\s+FHD\s+""", RegexOption.IGNORE_CASE) to Quality.Hevc,
        Regex("""^HEVC\s+HD\s+""", RegexOption.IGNORE_CASE) to Quality.HevcHd,
        Regex("""^HEVC\s+""", RegexOption.IGNORE_CASE) to Quality.Hevc,
        Regex("""^NEW\s+UKHD\s*:?\s*""", RegexOption.IGNORE_CASE) to Quality.Hd,
        Regex("""^NEW\s+UKSD\s*:?\s*""", RegexOption.IGNORE_CASE) to Quality.Sd,
        Regex("""^UK\s*\+\d+s?\s*:?\s*""", RegexOption.IGNORE_CASE) to Quality.Standard,
        Regex("""^UK\s+FHD\s+:?\s*""", RegexOption.IGNORE_CASE) to Quality.Fhd,
        Regex("""^UKFHD\s*:?\s*""", RegexOption.IGNORE_CASE) to Quality.Fhd,
        Regex("""^FHD\s*\|?\s*""", RegexOption.IGNORE_CASE) to Quality.Fhd,
        Regex("""^UKHD\s*:?\s*""", RegexOption.IGNORE_CASE) to Quality.Hd,
        Regex("""^UKSD\s*:?\s*""", RegexOption.IGNORE_CASE) to Quality.Sd,
        Regex("""^UK:\s*""", RegexOption.IGNORE_CASE) to Quality.Standard,
    )

    private fun stripAllPrefixes(name: String): String {
        for ((pattern, _) in prefixPatterns) {
            if (pattern.containsMatchIn(name)) {
                return name.replaceFirst(pattern, "").trim()
            }
        }
        return name
    }

    private data class Parsed(val name: String, val quality: Quality)

    private data class Ranked<T>(val channel: T, val quality: Quality)

    /**
     * Strip quality prefix, lowercase, normalise common channel
     * variants, then strip regional suffixes — everything left is the
     * canonical name we use to group duplicates.
     */
    private fun parse(raw: String): Parsed {
        var name = raw.trim()
        var quality = Quality.Standard

        // 1. Strip the quality/region prefix and remember which tier
        //    we've seen.
        for ((pattern, q) in prefixPatterns) {
            if (pattern.containsMatchIn(name)) {
                name = name.replaceFirst(pattern, "").trim()
                quality = q
                break
            }
        }

        // 2. Lowercase + collapse whitespace to canonical form.
        var n = name.lowercase().replace(Regex("""\s+"""), " ").trim()

        // 3. Strip trailing tags.
        n = n.replace(Regex("""\s*\[vip\]"""), "")
        n = n.replace(Regex("""\s*\(en\)"""), "")
        n = n.replace(Regex("""\s*\(s\)"""), "")
        n = n.replaceFirst(Regex("""(?:\s+(?:hd|sd|fhd|uhd|hevc))+\s*$"""), "")
        n = n.replaceFirst(Regex("""\s+\d+fps\s*$"""), "")
        n = n.replaceFirst(Regex("""\s+uk\s+version\s*$"""), "")

        // 4. Normalise common channel name variants so e.g. "BBC One"
        //    and "BBC1" both become "bbc 1".  The +1 timeshift
        //    spacing rules also live here so all three of "itv2+1",
        //    "itv 2+1", "itv 2 + 1" agree.
        n = n
            .replace("bbc one", "bbc 1").replace("bbc1", "bbc 1")
            .replace("bbc two", "bbc 2").replace("bbc2", "bbc 2")
            .replace("bbc three", "bbc 3").replace("bbc3", "bbc 3")
            .replace("bbc four", "bbc 4").replace("bbc4", "bbc 4")
            .replace("itv1", "itv 1").replace("itv2", "itv 2")
            .replace("itv3", "itv 3").replace("itv4", "itv 4")
            .replace("channel4", "channel 4").replace("channel5", "channel 5")
            .replace("5star", "5 star").replace("5usa", "5 usa")
            .replace("nick.jr", "nick jr")
            .replace(Regex("""(\d)\+""")) { "${it.groupValues[1]} +" }
            .replace(Regex("""([a-z])\+""")) { "${it.groupValues[1]} +" }
            .replace(Regex("""\+\s+(\d)""")) { "+${it.groupValues[1]}" }

        // 5. Strip "UK +1s:" and "UK +1" combinations.
        n = n.replace(Regex("""^uk\s*\+\d+s?\s*:?\s*"""), "")
        n = n.replace(Regex("""\s*uk\s*\+\d+s?\b"""), "")

        // 6. Strip regional suffixes.  Compound regions MUST appear
        //    before their single-word components — Kotlin (like Dart)
        //    regex alternation is left-to-right first-match.  The
        //    capture group preserves a trailing `+N` timeshift so
        //    "Channel 4 London +1" reduces to "channel 4 +1".
        // Kotlin's `String.replaceFirst(Regex, String)` only accepts a
        // literal replacement string, so we flip to the
        // `Regex.replaceFirst(CharSequence, transform)` extension which
        // does take a lambda — and lets us preserve the optional `+N`
        // timeshift capture group.
        val regionalSuffixPattern = Regex(
            """\s+""" +
                "(?:" +
                """northern ireland|""" +
                """south\s*&?\s*east|south west|south\s*&?\s*west|""" +
                """north\s*&?\s*east|north east|north west|north\s*&?\s*west|""" +
                """east midlands|west midlands|""" +
                """central west|central east|""" +
                """north east & cumbria|east yorkshire|""" +
                """yorks & lincs|yorkshire & lincolnshire|""" +
                """channel islands|""" +
                """s\s+west\s+sd|s\s+west|s\s+east|""" +
                """london|scotland|wales|ireland|""" +
                """south|north|east|west|central|""" +
                """anglia|meridian|yorkshire|ulster|border|oxford|""" +
                """tyne|granada|cymru""" +
                ")" +
                """(?:\s+(?:sd|hd|fhd))?""" +
                """(\s*\+\d+)?""" +
                """\s*$""",
        )
        n = regionalSuffixPattern.replace(n) { match ->
            match.groupValues.getOrElse(1) { "" }
        }

        // 7. Strip parenthetical / slash suffixes ("starts (7pm)/", "(EN)").
        n = n.replaceFirst(Regex("""\s+starts\s+\(.*$"""), "")
        n = n.replaceFirst(Regex("""\s*/\s+.*$"""), "")
        n = n.replaceFirst(Regex("""\s*\(.*?\)\s*$"""), "")

        return Parsed(n.trim(), quality)
    }
}
