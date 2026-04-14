package com.strata.tv.data.tmdb

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pure-logic tests for the helper functions used by
 * [MovieEnrichmentService] and [SeriesEnrichmentService].
 *
 * These run in the JVM test sourceSet — no Android runtime needed.
 */
class EnrichmentHelpersTest {

    // -----------------------------------------------------------------
    // formatCast
    // -----------------------------------------------------------------

    @Test
    fun `formatCast returns top 5 names comma separated`() {
        val credits = TmdbCredits(
            cast = listOf(
                TmdbCastMember("Alice", order = 0),
                TmdbCastMember("Bob", order = 1),
                TmdbCastMember("Charlie", order = 2),
                TmdbCastMember("Diana", order = 3),
                TmdbCastMember("Eve", order = 4),
                TmdbCastMember("Frank", order = 5),
            ),
        )

        val result = MovieEnrichmentService.formatCast(credits)
        assertThat(result).isEqualTo("Alice, Bob, Charlie, Diana, Eve")
    }

    @Test
    fun `formatCast sorts by order field`() {
        val credits = TmdbCredits(
            cast = listOf(
                TmdbCastMember("Second", order = 1),
                TmdbCastMember("First", order = 0),
                TmdbCastMember("Third", order = 2),
            ),
        )

        val result = MovieEnrichmentService.formatCast(credits)
        assertThat(result).isEqualTo("First, Second, Third")
    }

    @Test
    fun `formatCast returns empty string for null credits`() {
        assertThat(MovieEnrichmentService.formatCast(null)).isEmpty()
    }

    @Test
    fun `formatCast returns empty string for empty cast list`() {
        val credits = TmdbCredits(cast = emptyList())
        assertThat(MovieEnrichmentService.formatCast(credits)).isEmpty()
    }

    @Test
    fun `formatCast handles fewer than 5 cast members`() {
        val credits = TmdbCredits(
            cast = listOf(
                TmdbCastMember("Solo Actor", order = 0),
            ),
        )

        assertThat(MovieEnrichmentService.formatCast(credits)).isEqualTo("Solo Actor")
    }

    // -----------------------------------------------------------------
    // pickUsCertification (movies)
    // -----------------------------------------------------------------

    @Test
    fun `pickUsCertification finds US entry`() {
        val wrapper = TmdbReleaseDatesWrapper(
            results = listOf(
                TmdbReleaseDateCountry(
                    iso = "GB",
                    releaseDates = listOf(TmdbReleaseDateEntry("15")),
                ),
                TmdbReleaseDateCountry(
                    iso = "US",
                    releaseDates = listOf(TmdbReleaseDateEntry("PG-13")),
                ),
            ),
        )

        assertThat(MovieEnrichmentService.pickUsCertification(wrapper))
            .isEqualTo("PG-13")
    }

    @Test
    fun `pickUsCertification skips blank certifications`() {
        val wrapper = TmdbReleaseDatesWrapper(
            results = listOf(
                TmdbReleaseDateCountry(
                    iso = "US",
                    releaseDates = listOf(
                        TmdbReleaseDateEntry(""),
                        TmdbReleaseDateEntry("R"),
                    ),
                ),
            ),
        )

        assertThat(MovieEnrichmentService.pickUsCertification(wrapper))
            .isEqualTo("R")
    }

    @Test
    fun `pickUsCertification returns empty when no US entry`() {
        val wrapper = TmdbReleaseDatesWrapper(
            results = listOf(
                TmdbReleaseDateCountry(
                    iso = "FR",
                    releaseDates = listOf(TmdbReleaseDateEntry("U")),
                ),
            ),
        )

        assertThat(MovieEnrichmentService.pickUsCertification(wrapper)).isEmpty()
    }

    @Test
    fun `pickUsCertification returns empty for null wrapper`() {
        assertThat(MovieEnrichmentService.pickUsCertification(null)).isEmpty()
    }

    // -----------------------------------------------------------------
    // pickUsTvRating (series)
    // -----------------------------------------------------------------

    @Test
    fun `pickUsTvRating finds US entry`() {
        val wrapper = TmdbContentRatingsWrapper(
            results = listOf(
                TmdbContentRating(iso = "GB", rating = "15"),
                TmdbContentRating(iso = "US", rating = "TV-MA"),
            ),
        )

        assertThat(SeriesEnrichmentService.pickUsTvRating(wrapper))
            .isEqualTo("TV-MA")
    }

    @Test
    fun `pickUsTvRating returns empty when no US entry`() {
        val wrapper = TmdbContentRatingsWrapper(
            results = listOf(
                TmdbContentRating(iso = "DE", rating = "16"),
            ),
        )

        assertThat(SeriesEnrichmentService.pickUsTvRating(wrapper)).isEmpty()
    }

    @Test
    fun `pickUsTvRating returns empty for null wrapper`() {
        assertThat(SeriesEnrichmentService.pickUsTvRating(null)).isEmpty()
    }

    @Test
    fun `pickUsTvRating returns empty for empty results`() {
        val wrapper = TmdbContentRatingsWrapper(results = emptyList())
        assertThat(SeriesEnrichmentService.pickUsTvRating(wrapper)).isEmpty()
    }
}
