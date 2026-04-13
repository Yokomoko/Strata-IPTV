package com.strata.tv.domain

/**
 * Sub-categorises live channels into TV-Guide-friendly buckets.
 *
 * Direct port of `lib/services/channel_categorizer.dart` from
 * Strata v1, with the v1 issue backlog folded in:
 *
 * - Yokomoko/Strata#28 — narrow sports verticals (F1, Golf, Cricket,
 *   …) collapse into a single `Sports` category instead of being
 *   their own top-level chips.
 * - Yokomoko/Strata#26 — Shopping and International get their own
 *   buckets so QVC and the sub-continent / Arabic channels don't
 *   pollute Entertainment.
 *
 * The matching is heuristic — substring against the channel's
 * display name after stripping known quality / region prefixes.  The
 * categoriser only fires for channels in the broad
 * `"United Kingdom"` group; provider-specific groups (e.g. "Peacock
 * TV", "Paramount Plus") are passed through unchanged.
 */
object ChannelCategorizer {

    /**
     * Display order for the TV Guide category bar.  Anything not in
     * this list gets sorted alphabetically at the end.
     */
    val displayOrder: List<String> = listOf(
        "All",
        "Entertainment",
        "Sports",
        "Cinema",
        "News",
        "Kids",
        "Documentary",
        "Music",
        "Shopping",
        "International",
        "General",
    )

    /**
     * Substring keywords per category.  First match wins, evaluated
     * in [displayOrder] order so e.g. "sky cinema" is classified as
     * Cinema before any Entertainment rule has a chance to match it.
     */
    private val rules: Map<String, List<String>> = mapOf(
        "Sports" to listOf(
            "sky sports", "tnt sport", "bt sport", "eurosport", "espn",
            "premier sports", "racing", "at the races", "mutv", "lfctv", "lfc",
            "setanta", "supersport", "box office", "dazn", "viaplay",
            // v1 #28 — fold narrow sports verticals into Sports.
            "formula 1", "formula1", " f1 ", "f1 tv",
            "golf", "tennis", "cricket", "boxing", "ufc",
        ),
        "News" to listOf(
            "bbc news", "sky news", "gb news", "al jazeera", "cnn", "fox news",
            "cnbc", "bloomberg", "euronews", "france 24", "nhk world",
            "talk tv", "tvc news", "ndtv", "court tv",
        ),
        "Cinema" to listOf(
            "sky cinema", "film4", "film 4", "movies4men", "movies 24",
            "great movies", "talking pictures", "talkingpictures",
            "sony movies", "horror channel", "great tv",
        ),
        "Kids" to listOf(
            "cbeebies", "cbbc", "cartoon network", "cartoonito",
            "nickelodeon", "nick jr", "nick.jr", "nicktoons", "nick toons",
            "baby tv", "boomerang", "pop", "sky kids", "peppa pig",
            "disney jr", "disney junior",
        ),
        "Music" to listOf(
            "mtv", "vh1", "kiss", "kerrang", "box hits", "the box",
            "clubland", "now music", "now 70s", "now 80s", "now 90s",
            "trace", "magic",
        ),
        "Documentary" to listOf(
            "discovery", "national geo", "nat geo", "history",
            "eden", "animal pl", "quest", "smithsonian",
            "dmax", "yesterday", "crime inv", "true crime",
            "rewind tv",
        ),
        "Shopping" to listOf(
            // v1 #26.
            "qvc", "ideal world", "ideal extra", "tjc", "create and craft",
            "rocks tv", "high street tv",
        ),
        "International" to listOf(
            // v1 #26.
            "aaj tak", "ary digital", "geo news", "geo tv",
            "hum europe", "hum sitaray", "hum style",
            "ptc punjabi", "sky news arabia",
            "ptv", "zee", "colors",
        ),
        "Entertainment" to listOf(
            "bbc1", "bbc 1", "bbc one", "bbc2", "bbc 2", "bbc two",
            "bbc three", "bbc four", "bbc red",
            "itv1", "itv 1", "itv2", "itv 2", "itv3", "itv 3", "itv4", "itv 4",
            "channel 4", "channel 5", "channel4", "channel5",
            "e4", "more4", "4seven", "5star", "5usa", "5select",
            "sky one", "sky atlantic", "sky witness", "sky max", "sky syfy",
            "sky comedy", "sky crime", "sky arts", "sky showcase",
            "dave", "gold", "alibi", "drama", "really", "virgin",
            "comedy central", "tlc", "food network",
            "london live", "s4c", "stv",
        ),
    )

    /** Pre-compiled prefix-stripper for channel names — same patterns
     *  as `ChannelDeduplicator.dedupe`'s prefix list, packed into one
     *  regex for the per-name match path. */
    private val qualityPrefix = Regex(
        "^(new\\s+uk[hs]d\\s*:?\\s*|" +
            "uk\\s*\\+\\d+s?\\s*:?\\s*|" +
            "uk\\s+fhd\\s+:?\\s*|" +
            "uk:|ukhd|uksd|ukfhd|" +
            "hevc\\s+fhd|hevc\\s+hd|hevc|" +
            "fhd\\s*\\|?)\\s*",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Classify [displayName] within its [groupTitle].
     *
     * - For non-"United Kingdom" groups, returns the group title
     *   verbatim — providers usually pre-categorise their own
     *   verticals (e.g. "Peacock TV", "US Amazon Prime Linear")
     *   sensibly enough.
     * - For UK channels, strips quality prefixes and matches
     *   substrings against [rules].  First category to hit wins.
     * - Anything UK that doesn't match any rule lands in `"General"`.
     */
    fun categorise(displayName: String, groupTitle: String): String {
        if (groupTitle != "United Kingdom") return groupTitle

        val stripped = displayName.lowercase().replace(qualityPrefix, "").trim()

        for (cat in displayOrder) {
            val patterns = rules[cat] ?: continue
            for (p in patterns) {
                if (stripped.contains(p)) return cat
            }
        }

        // Amazon UK linear channels → Entertainment, even though they
        // aren't matched by any rule above.
        if ("amazon uk" in displayName.lowercase()) return "Entertainment"

        return "General"
    }
}
