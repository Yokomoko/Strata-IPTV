package com.strata.tv.data.tmdb

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test

/**
 * Spot-checks that the new TMDB detail models deserialise correctly.
 *
 * Uses the same `Json { ignoreUnknownKeys = true }` config as
 * [com.strata.tv.data.tmdb.TmdbModule] so the test exercises the
 * exact same parsing path Retrofit will use at runtime.
 */
class TmdbModelTest {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    // -----------------------------------------------------------------
    // Movie detail
    // -----------------------------------------------------------------

    @Test
    fun `movie detail deserialises core fields`() {
        val raw = """
        {
          "id": 550,
          "title": "Fight Club",
          "overview": "A ticking-Loss bomb insomniac and a slippery soap salesman...",
          "runtime": 139,
          "backdrop_path": "/hZkgoQYus5dXo3H8T7Uef6DNknx.jpg",
          "release_date": "1999-10-15",
          "genres": [
            {"id": 18, "name": "Drama"},
            {"id": 53, "name": "Thriller"}
          ],
          "credits": {
            "cast": [
              {"name": "Edward Norton", "order": 0},
              {"name": "Brad Pitt", "order": 1},
              {"name": "Meat Loaf", "order": 2},
              {"name": "Zach Grenier", "order": 3},
              {"name": "Helena Bonham Carter", "order": 4},
              {"name": "Jared Leto", "order": 5}
            ]
          },
          "release_dates": {
            "results": [
              {
                "iso_3166_1": "US",
                "release_dates": [
                  {"certification": "R", "type": 3}
                ]
              },
              {
                "iso_3166_1": "GB",
                "release_dates": [
                  {"certification": "18", "type": 3}
                ]
              }
            ]
          }
        }
        """.trimIndent()

        val detail = json.decodeFromString<TmdbMovieDetail>(raw)

        assertThat(detail.id).isEqualTo(550)
        assertThat(detail.title).isEqualTo("Fight Club")
        assertThat(detail.overview).startsWith("A ticking")
        assertThat(detail.runtime).isEqualTo(139)
        assertThat(detail.backdropPath).isEqualTo("/hZkgoQYus5dXo3H8T7Uef6DNknx.jpg")
        assertThat(detail.releaseDate).isEqualTo("1999-10-15")
        assertThat(detail.genres).hasSize(2)
        assertThat(detail.genres[0].name).isEqualTo("Drama")
        assertThat(detail.credits?.cast).hasSize(6)
        assertThat(detail.releaseDates?.results).hasSize(2)
    }

    @Test
    fun `movie detail handles missing optional fields gracefully`() {
        val raw = """{"id": 999}"""
        val detail = json.decodeFromString<TmdbMovieDetail>(raw)

        assertThat(detail.id).isEqualTo(999)
        assertThat(detail.overview).isNull()
        assertThat(detail.runtime).isNull()
        assertThat(detail.backdropPath).isNull()
        assertThat(detail.genres).isEmpty()
        assertThat(detail.credits).isNull()
        assertThat(detail.releaseDates).isNull()
    }

    // -----------------------------------------------------------------
    // TV detail
    // -----------------------------------------------------------------

    @Test
    fun `tv detail deserialises core fields`() {
        val raw = """
        {
          "id": 1396,
          "name": "Breaking Bad",
          "overview": "A high school chemistry teacher diagnosed with...",
          "backdrop_path": "/tsRy63Mu5cu8etL1X7ZLyf7UP1M.jpg",
          "first_air_date": "2008-01-20",
          "genres": [
            {"id": 18, "name": "Drama"},
            {"id": 80, "name": "Crime"}
          ],
          "credits": {
            "cast": [
              {"name": "Bryan Cranston", "order": 0},
              {"name": "Aaron Paul", "order": 1},
              {"name": "Anna Gunn", "order": 2}
            ]
          },
          "content_ratings": {
            "results": [
              {"iso_3166_1": "US", "rating": "TV-MA"},
              {"iso_3166_1": "GB", "rating": "15"}
            ]
          }
        }
        """.trimIndent()

        val detail = json.decodeFromString<TmdbTvDetail>(raw)

        assertThat(detail.id).isEqualTo(1396)
        assertThat(detail.name).isEqualTo("Breaking Bad")
        assertThat(detail.overview).startsWith("A high school")
        assertThat(detail.backdropPath).isEqualTo("/tsRy63Mu5cu8etL1X7ZLyf7UP1M.jpg")
        assertThat(detail.firstAirDate).isEqualTo("2008-01-20")
        assertThat(detail.genres).hasSize(2)
        assertThat(detail.credits?.cast).hasSize(3)
        assertThat(detail.contentRatings?.results).hasSize(2)
    }

    @Test
    fun `tv detail handles missing optional fields gracefully`() {
        val raw = """{"id": 42}"""
        val detail = json.decodeFromString<TmdbTvDetail>(raw)

        assertThat(detail.id).isEqualTo(42)
        assertThat(detail.overview).isNull()
        assertThat(detail.backdropPath).isNull()
        assertThat(detail.genres).isEmpty()
        assertThat(detail.credits).isNull()
        assertThat(detail.contentRatings).isNull()
    }
}
