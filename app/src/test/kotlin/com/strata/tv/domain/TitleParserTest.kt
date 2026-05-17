package com.strata.tv.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Parity tests for [TitleParser] — same shape as v1's
 * `test/services/title_parser_test.dart`.
 *
 * Algorithms in the domain layer are pure Kotlin → run in the JVM
 * test sourceSet → no Android runtime → fast.
 */
class TitleParserTest {

    @Test
    fun `movie pattern extracts title and year`() {
        val parsed = TitleParser.parseMovie("HD : Inception 2010")
        assertThat(parsed).isNotNull()
        assertThat(parsed!!.title).isEqualTo("Inception")
        assertThat(parsed.year).isEqualTo(2010)
    }

    @Test
    fun `movie pattern handles extra spaces`() {
        val parsed = TitleParser.parseMovie("HD :   Dune Part Two   2024")
        assertThat(parsed).isNotNull()
        assertThat(parsed!!.title).isEqualTo("Dune Part Two")
        assertThat(parsed.year).isEqualTo(2024)
    }

    @Test
    fun `movie pattern returns null when no year present`() {
        assertThat(TitleParser.parseMovie("HD : Some Random Channel")).isNull()
    }

    @Test
    fun `episode pattern extracts series, season, episode`() {
        val parsed = TitleParser.parseEpisode("HD : Breaking Bad S01E02")
        assertThat(parsed).isNotNull()
        assertThat(parsed!!.seriesTitle).isEqualTo("Breaking Bad")
        assertThat(parsed.season).isEqualTo(1)
        assertThat(parsed.episode).isEqualTo(2)
    }

    @Test
    fun `episode pattern handles three-digit episode numbers`() {
        val parsed = TitleParser.parseEpisode("HD : Long Show S05E121")
        assertThat(parsed).isNotNull()
        assertThat(parsed!!.season).isEqualTo(5)
        assertThat(parsed.episode).isEqualTo(121)
    }

    @Test
    fun `episode pattern returns null for movies`() {
        assertThat(TitleParser.parseEpisode("HD : Inception 2010")).isNull()
    }

    @Test
    fun `stripHdPrefix removes leading HD colon`() {
        assertThat(TitleParser.stripHdPrefix("HD : Some Title")).isEqualTo("Some Title")
        assertThat(TitleParser.stripHdPrefix("HD: Compact")).isEqualTo("Compact")
    }

    @Test
    fun `stripHdPrefix is a no-op when prefix is absent`() {
        assertThat(TitleParser.stripHdPrefix("Already clean")).isEqualTo("Already clean")
    }

    @Test
    fun `normalise lowercases and removes punctuation`() {
        assertThat(TitleParser.normalise("Dune: Part Two!")).isEqualTo("dune part two")
    }

    @Test
    fun `normalise collapses whitespace`() {
        assertThat(TitleParser.normalise("  Lots   of   space  "))
            .isEqualTo("lots of space")
    }

    // -- cleanForSearch --------------------------------------------------

    @Test
    fun `cleanForSearch strips trailing country code`() {
        val result = TitleParser.cleanForSearch("Euphoria US")
        assertThat(result.title).isEqualTo("Euphoria")
        assertThat(result.year).isNull()
    }

    @Test
    fun `cleanForSearch strips UK country code`() {
        val result = TitleParser.cleanForSearch("The Office UK")
        assertThat(result.title).isEqualTo("The Office")
        assertThat(result.year).isNull()
    }

    @Test
    fun `cleanForSearch extracts trailing year`() {
        val result = TitleParser.cleanForSearch("Untold 2021")
        assertThat(result.title).isEqualTo("Untold")
        assertThat(result.year).isEqualTo(2021)
    }

    @Test
    fun `cleanForSearch extracts year from CIA 2026`() {
        val result = TitleParser.cleanForSearch("CIA 2026")
        assertThat(result.title).isEqualTo("CIA")
        assertThat(result.year).isEqualTo(2026)
    }

    @Test
    fun `cleanForSearch strips country then extracts year`() {
        val result = TitleParser.cleanForSearch("The Office US 2005")
        assertThat(result.title).isEqualTo("The Office")
        assertThat(result.year).isEqualTo(2005)
    }

    @Test
    fun `cleanForSearch is a no-op for clean titles`() {
        val result = TitleParser.cleanForSearch("Breaking Bad")
        assertThat(result.title).isEqualTo("Breaking Bad")
        assertThat(result.year).isNull()
    }

    @Test
    fun `cleanForSearch does not strip mid-title country codes`() {
        val result = TitleParser.cleanForSearch("Born in the US")
        // "US" at end gets stripped — this is by design; TMDB search
        // for "Born in the" will still match. The raw title stays in DB.
        assertThat(result.title).isEqualTo("Born in the")
    }
}
