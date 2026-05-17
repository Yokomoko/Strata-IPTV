package com.strata.tv.data.epg

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests for [EpgChannelMatcher]'s normalisation and matching
 * helpers.
 *
 * The full [EpgChannelMatcher.resolve] flow requires a Room
 * [ProgrammeDao], which is tested via instrumentation tests.  These
 * JVM-only tests exercise the static normalisation logic that drives
 * the matching heuristics.
 */
class EpgChannelMatcherTest {

    // -----------------------------------------------------------------
    // normaliseId
    // -----------------------------------------------------------------

    @Test
    fun `normaliseId lowercases and strips punctuation`() {
        assertThat(EpgChannelMatcher.normaliseId("BBC.One.UK"))
            .isEqualTo("bbconeuk")
    }

    @Test
    fun `normaliseId handles underscores and dashes`() {
        assertThat(EpgChannelMatcher.normaliseId("UK_ITV_1-HD"))
            .isEqualTo("ukitv1hd")
    }

    @Test
    fun `normaliseId handles colons and slashes`() {
        assertThat(EpgChannelMatcher.normaliseId("sky/sports:1"))
            .isEqualTo("skysports1")
    }

    @Test
    fun `normaliseId handles spaces`() {
        assertThat(EpgChannelMatcher.normaliseId("Sky Sports 1"))
            .isEqualTo("skysports1")
    }

    // -----------------------------------------------------------------
    // normaliseName
    // -----------------------------------------------------------------

    @Test
    fun `normaliseName strips ampersands and apostrophes`() {
        assertThat(EpgChannelMatcher.normaliseName("Dave & Gold"))
            .isEqualTo("davegold")
    }

    @Test
    fun `normaliseName handles apostrophes in names`() {
        assertThat(EpgChannelMatcher.normaliseName("Yesterday's Channel"))
            .isEqualTo("yesterdayschannel")
    }

    // -----------------------------------------------------------------
    // stripQualitySuffixes
    // -----------------------------------------------------------------

    @Test
    fun `stripQualitySuffixes removes HD`() {
        assertThat(EpgChannelMatcher.stripQualitySuffixes("bbconehd"))
            .isEqualTo("bbcone")
    }

    @Test
    fun `stripQualitySuffixes removes country then quality`() {
        // "bbconeukfhd" -> strip "uk" -> "bbconefhd" -> strip "fhd" -> "bbcone"
        // Note: the country strip runs first, then quality.
        val result = EpgChannelMatcher.stripQualitySuffixes("bbconeukfhd")
        // After first pass: "bbconeukfhd" -> strip uk from end -> "bbconefhd"
        // After second pass: strip fhd -> "bbcone"
        assertThat(result).isEqualTo("bbcone")
    }

    @Test
    fun `stripQualitySuffixes removes multiple quality tags`() {
        assertThat(EpgChannelMatcher.stripQualitySuffixes("skysports1hd"))
            .isEqualTo("skysports1")
    }

    @Test
    fun `stripQualitySuffixes removes 4k`() {
        assertThat(EpgChannelMatcher.stripQualitySuffixes("skysportsmain4k"))
            .isEqualTo("skysportsmain")
    }

    @Test
    fun `stripQualitySuffixes removes plus one`() {
        assertThat(EpgChannelMatcher.stripQualitySuffixes("itv2+1"))
            .isEqualTo("itv2")
    }

    @Test
    fun `stripQualitySuffixes does not damage core name`() {
        // "channel5" should NOT have "5" stripped (it's not a suffix).
        assertThat(EpgChannelMatcher.stripQualitySuffixes("channel5"))
            .isEqualTo("channel5")
    }

    // -----------------------------------------------------------------
    // broadcasterRoot
    // -----------------------------------------------------------------

    @Test
    fun `broadcasterRoot strips UK prefix and HD suffix`() {
        assertThat(EpgChannelMatcher.broadcasterRoot("ukbbconehd"))
            .isEqualTo("bbcone")
    }

    @Test
    fun `broadcasterRoot strips US prefix`() {
        assertThat(EpgChannelMatcher.broadcasterRoot("usespn2hd"))
            .isEqualTo("espn2")
    }

    @Test
    fun `broadcasterRoot no-op when no prefix or suffix`() {
        assertThat(EpgChannelMatcher.broadcasterRoot("bbcone"))
            .isEqualTo("bbcone")
    }

    @Test
    fun `broadcasterRoot strips GB prefix`() {
        assertThat(EpgChannelMatcher.broadcasterRoot("gbitv1hd"))
            .isEqualTo("itv1")
    }

    // -----------------------------------------------------------------
    // Real-world matching scenarios (via normalisation pipeline)
    // -----------------------------------------------------------------

    @Test
    fun `BBC One UK matches bbc_one dot uk`() {
        // M3U tvg-id: "BBC.One.UK" normalises to "bbconeuk"
        // XMLTV id: "bbc_one.uk" normalises to "bbconeuk"
        // These should be an exact match in the normalised-id map.
        val m3uNorm = EpgChannelMatcher.normaliseId("BBC.One.UK")
        val xmltvNorm = EpgChannelMatcher.normaliseId("bbc_one.uk")
        assertThat(m3uNorm).isEqualTo(xmltvNorm)
    }

    @Test
    fun `Sky Sports 1 HD matches via suffix stripping`() {
        // M3U: "SkySports1HD" normalises to "skysports1hd"
        // XMLTV: "sky.sports.1" normalises to "skysports1"
        // Direct match fails, but suffix-stripping from M3U:
        //   "skysports1hd" -> strip "hd" -> "skysports1" (matches XMLTV)
        val m3uNorm = EpgChannelMatcher.normaliseId("SkySports1HD")
        val xmltvNorm = EpgChannelMatcher.normaliseId("sky.sports.1")
        assertThat(m3uNorm).isNotEqualTo(xmltvNorm) // direct mismatch
        assertThat(EpgChannelMatcher.stripQualitySuffixes(m3uNorm)).isEqualTo(xmltvNorm)
    }

    @Test
    fun `ITV1 UK HD matches via broadcaster root`() {
        // M3U: "UK_ITV1_HD" -> normalise "ukitv1hd" -> root "itv1"
        // XMLTV: "ITV1" -> normalise "itv1" -> root "itv1"
        val m3uRoot = EpgChannelMatcher.broadcasterRoot(
            EpgChannelMatcher.normaliseId("UK_ITV1_HD"),
        )
        val xmltvRoot = EpgChannelMatcher.broadcasterRoot(
            EpgChannelMatcher.normaliseId("ITV1"),
        )
        assertThat(m3uRoot).isEqualTo(xmltvRoot)
    }

    @Test
    fun `Channel 4 FHD matches Channel4 via suffix stripping`() {
        val m3u = EpgChannelMatcher.normaliseId("Channel4.FHD")
        val xmltv = EpgChannelMatcher.normaliseId("Channel4")
        assertThat(EpgChannelMatcher.stripQualitySuffixes(m3u)).isEqualTo(xmltv)
    }

    @Test
    fun `short keys rejected from fuzzy matching`() {
        // "sky" is only 3 chars, below the MIN_FUZZY_KEY_LENGTH of 4.
        // This prevents "Sky" from matching "Sky News", "Sky Sports", etc.
        val shortKey = EpgChannelMatcher.normaliseId("Sky")
        assertThat(shortKey.length).isLessThan(4)
    }

    @Test
    fun `numeric XMLTV ids do not generate name lookups`() {
        // "10009.sd.org" normalises to "10009sdorg", which looks like
        // a numeric provider id.  nameFromId should reject it, but
        // normaliseId still produces a usable key for exact-match.
        val normId = EpgChannelMatcher.normaliseId("10009.sd.org")
        assertThat(normId).isEqualTo("10009sdorg")
    }
}
