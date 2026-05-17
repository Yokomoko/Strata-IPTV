package com.strata.tv.domain

/**
 * Maps a channel display name to its Sky-style channel number.
 *
 * Used by the dedup tie-breaker and by the TV Guide to order
 * channels in the Sky-natural order.  Direct port of v1's
 * `lib/services/sky_channel_numbers.dart`, kept verbatim so any
 * downstream sort behaves identically.  v1 issue #27 asked for the
 * map to be expanded — that work folds in here over time as we add
 * coverage for Sky Atlantic, TNT Sports, etc.
 *
 * Lookup is name-insensitive: the internal map is keyed by
 * lowercased + normalised display names so callers can pass the raw
 * channel name through.
 */
object SkyChannelNumbers {

    /** Sentinel returned when no number is known — sorts to the end. */
    const val UNKNOWN: Int = 999

    private val map: Map<String, Int> = buildMap {
        // BBC.
        put("bbc 1", 101)
        put("bbc one", 101)
        put("bbc1", 101)
        put("bbc 2", 102)
        put("bbc two", 102)
        put("bbc2", 102)
        put("itv", 103)
        put("itv 1", 103)
        put("itv1", 103)
        put("channel 4", 104)
        put("channel4", 104)
        put("channel 5", 105)
        put("channel5", 105)
        put("sky showcase", 106)
        put("sky max", 110)
        put("sky witness", 108)
        put("sky atlantic", 109)
        put("sky comedy", 111)
        put("sky crime", 112)
        put("sky documentaries", 114)
        put("sky history", 116)
        put("sky nature", 119)
        put("sky arts", 121)
        put("sky kids", 643)
        put("sky news", 501)

        // ITV family.
        put("itv 2", 118)
        put("itv2", 118)
        put("itv 3", 119)
        put("itv3", 119)
        put("itv 4", 120)
        put("itv4", 120)
        put("itvbe", 131)

        // Channel 4 family.
        put("e4", 135)
        put("more4", 136)
        put("more 4", 136)
        put("4seven", 138)
        put("film4", 313)
        put("film 4", 313)

        // Channel 5 family.
        put("5star", 128)
        put("5 star", 128)
        put("5usa", 141)
        put("5 usa", 141)
        put("5select", 152)

        // Music + entertainment.
        put("dave", 111)
        put("gold", 113)
        put("alibi", 132)
        put("drama", 143)
        put("really", 142)
        put("comedy central", 132)

        // Sports.
        put("sky sports main event", 401)
        put("sky sports premier league", 402)
        put("sky sports football", 403)
        put("sky sports cricket", 404)
        put("sky sports golf", 405)
        put("sky sports f1", 406)
        put("sky sports tennis", 407)
        put("sky sports action", 408)
        put("sky sports arena", 409)
        put("tnt sports 1", 410)
        put("tnt sports 2", 411)
        put("tnt sports 3", 412)
        put("tnt sports 4", 413)

        // Cinema.
        put("sky cinema premiere", 301)
        put("sky cinema hits", 303)
        put("sky cinema action", 305)
        put("sky cinema comedy", 306)
        put("sky cinema family", 307)
        put("sky cinema thriller", 311)
        put("sky cinema drama", 309)
        put("sky cinema sci-fi horror", 312)
        put("sky cinema animation", 308)
    }

    /** Returns the Sky number for [displayName], or [UNKNOWN] when unmapped. */
    fun numberFor(displayName: String): Int {
        val key = displayName.lowercase().replace(Regex("""\s+"""), " ").trim()
        return map[key] ?: UNKNOWN
    }
}
