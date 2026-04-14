package com.strata.tv.domain

/**
 * Sub-categorises live channels into TV-Guide-friendly buckets.
 *
 * All channels are classified into exactly one of 8 user-facing
 * categories (plus "All" which shows everything).  Non-UK groups
 * from the M3U (e.g. "Peacock TV", "Paramount Plus", "TBO") are
 * mapped into the appropriate category rather than passed through
 * as their own chips.
 */
object ChannelCategorizer {

    /** Display order for the TV Guide category chip bar. */
    val displayOrder: List<String> = listOf(
        "All",
        "Entertainment",
        "Cinema",
        "Sport",
        "Kids",
        "Music",
        "Documentaries",
        "Shopping",
        "International",
    )

    // -----------------------------------------------------------------
    // Group-title-level mapping — for non-UK groups that should map
    // to one of our categories. Case-insensitive substring match.
    // -----------------------------------------------------------------

    private val groupToCategory: Map<String, String> = mapOf(
        // Entertainment
        "peacock" to "Entertainment",
        "paramount" to "Entertainment",
        "tbo" to "Entertainment",
        "amazon prime linear" to "Entertainment",
        "hbo" to "Entertainment",
        "hulu" to "Entertainment",
        "apple tv" to "Entertainment",
        "roku" to "Entertainment",
        "pluto" to "Entertainment",
        "tubi" to "Entertainment",
        "britbox" to "Entertainment",
        // Sport
        "formula one" to "Sport",
        "formula 1" to "Sport",
        "golf" to "Sport",
        "cricket" to "Sport",
        "boxing" to "Sport",
        "wrestling" to "Sport",
        "tennis" to "Sport",
        "nfl" to "Sport",
        "nba" to "Sport",
        "mlb" to "Sport",
        // Cinema
        "cinema" to "Cinema",
        "movies" to "Cinema",
        // Kids
        "kids" to "Kids",
        // Music
        "music" to "Music",
        // Documentaries
        "documentary" to "Documentaries",
        "documentaries" to "Documentaries",
        // Shopping
        "shopping" to "Shopping",
        // International
        "international" to "International",
        "pakistan" to "International",
        "india" to "International",
        "arabic" to "International",
        "asian" to "International",
        "turkish" to "International",
        "polish" to "International",
        "african" to "International",
    )

    // -----------------------------------------------------------------
    // Channel-name-level rules — substring patterns per category,
    // matched against the stripped display name for UK channels.
    // -----------------------------------------------------------------

    private val rules: Map<String, List<String>> = mapOf(
        "Sport" to listOf(
            "sky sports", "tnt sport", "bt sport", "eurosport", "espn",
            "premier sports", "racing", "at the races", "mutv", "lfctv", "lfc",
            "setanta", "supersport", "box office", "dazn", "viaplay",
            "formula 1", "formula1", " f1 ", "f1 tv",
            "golf", "tennis", "cricket", "boxing", "ufc",
            "sky sport",
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
        "Documentaries" to listOf(
            "discovery", "national geo", "nat geo", "history",
            "eden", "animal pl", "quest", "smithsonian",
            "dmax", "yesterday", "crime inv", "true crime",
            "rewind tv",
        ),
        "Shopping" to listOf(
            "qvc", "ideal world", "ideal extra", "tjc", "create and craft",
            "rocks tv", "high street tv",
        ),
        "International" to listOf(
            "aaj tak", "ary digital", "geo news", "geo tv",
            "hum europe", "hum sitaray", "hum style",
            "ptc punjabi", "sky news arabia",
            "ptv", "zee", "colors",
        ),
        // Entertainment is the catch-all, but we still match known
        // UK entertainment channels explicitly so they sort before
        // the fallback.
        "Entertainment" to listOf(
            "bbc1", "bbc 1", "bbc one", "bbc2", "bbc 2", "bbc two",
            "bbc three", "bbc four", "bbc red", "bbc news",
            "itv1", "itv 1", "itv2", "itv 2", "itv3", "itv 3", "itv4", "itv 4",
            "channel 4", "channel 5", "channel4", "channel5",
            "e4", "more4", "4seven", "5star", "5usa", "5select",
            "sky one", "sky atlantic", "sky witness", "sky max", "sky syfy",
            "sky comedy", "sky crime", "sky arts", "sky showcase",
            "sky news", "gb news", "talk tv",
            "dave", "gold", "alibi", "drama", "really", "virgin",
            "comedy central", "tlc", "food network",
            "london live", "s4c", "stv",
            "al jazeera", "cnn", "fox news", "cnbc", "bloomberg",
            "euronews", "france 24", "nhk world",
        ),
    )

    /** Pre-compiled prefix-stripper for channel names. */
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
     * 1. For non-"United Kingdom" groups, map the group title to a
     *    known category via [groupToCategory], defaulting to
     *    Entertainment.
     * 2. For UK channels, strip quality prefixes and match substrings
     *    against [rules].  First category to hit wins.
     * 3. Anything that doesn't match any rule → Entertainment.
     */
    fun categorise(displayName: String, groupTitle: String): String {
        // Non-UK groups — map by group title.
        if (groupTitle != "United Kingdom") {
            val lower = groupTitle.lowercase()
            for ((keyword, cat) in groupToCategory) {
                if (lower.contains(keyword)) return cat
            }
            return "Entertainment" // Unknown non-UK group → Entertainment
        }

        // UK channels — match by display name.
        val stripped = displayName.lowercase().replace(qualityPrefix, "").trim()

        for (cat in displayOrder) {
            val patterns = rules[cat] ?: continue
            for (p in patterns) {
                if (stripped.contains(p)) return cat
            }
        }

        if ("amazon uk" in displayName.lowercase()) return "Entertainment"

        return "Entertainment" // Unmatched UK channels → Entertainment
    }
}
