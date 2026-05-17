package com.strata.tv.data.tmdb

import android.util.Log
import com.strata.tv.AppConfig
import com.strata.tv.data.db.EpisodeDao
import com.strata.tv.data.db.SeriesDao
import com.strata.tv.data.db.SeriesEntity
import com.strata.tv.data.settings.SettingsRepository
import com.strata.tv.domain.TitleParser
import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Three-pass TMDB enrichment for TV series — mirrors
 * [MovieEnrichmentService] but targets the `series` table.
 *
 * 1. **Search pass** — finds series missing poster, calls
 *    `/search/tv`, persists poster + genre + tmdbId.  Non-English
 *    titles are hidden (same parity rule as movies).
 * 2. **Detail pass** — for series that have a tmdbId but are still
 *    missing plot or backdrop, calls `/tv/{id}` with
 *    `append_to_response=credits,content_ratings` and persists
 *    plot, backdrop, cast, certification, first_air_year and genres.
 * 3. **Episode name pass** — for series with a tmdbId whose episodes
 *    still have empty titles, calls `/tv/{id}/season/{n}` for each
 *    season and updates `episode_title` from the TMDB episode name.
 */
@Singleton
class SeriesEnrichmentService @Inject constructor(
    private val tmdb: TmdbApi,
    private val seriesDao: SeriesDao,
    private val episodeDao: EpisodeDao,
    private val tracker: EnrichmentProgressTracker,
    private val settingsRepo: SettingsRepository,
) {

    /** Run all enrichment passes, looping until all items are done. */
    suspend fun enrichBatch() {
        searchPassAll()
        detailPassAll()
        episodeNamePassAll()
    }

    // -----------------------------------------------------------------
    // Pass 1 — search: poster, genre IDs, language, tmdbId
    // -----------------------------------------------------------------

    private suspend fun searchPassAll() {
        while (true) {
            val pending = seriesDao.needingEnrichment(limit = 50)
            if (pending.isEmpty()) break
            searchBatch(pending)
        }
    }

    private suspend fun searchBatch(pending: List<SeriesEntity>) {
        val wantedLanguages = settingsRepo.current().wantedLanguages
        for (series in pending) {
            runCatching {
                val cleaned = TitleParser.cleanForSearch(series.seriesTitle)
                val response = tmdb.searchTv(
                    apiKey = AppConfig.TMDB_API_KEY,
                    query = cleaned.title,
                    year = cleaned.year,
                )
                val match = response.results.firstOrNull() ?: return@runCatching
                val isWanted = wantedLanguages.isEmpty() ||
                    match.originalLanguage.orEmpty() in wantedLanguages

                seriesDao.updateMetadata(
                    title = series.seriesTitle,
                    poster = match.posterPath?.let {
                        "${AppConfig.TMDB_IMAGE_BASE}/${AppConfig.TMDB_POSTER_SIZE}$it"
                    } ?: "",
                    backdrop = match.backdropPath?.let {
                        "${AppConfig.TMDB_IMAGE_BASE}/${AppConfig.TMDB_BACKDROP_SIZE}$it"
                    } ?: "",
                    plot = match.overview.orEmpty(),
                    genre = match.genreIds.joinToString(", ") { tvGenreName(it) },
                    language = match.originalLanguage.orEmpty(),
                    hidden = !isWanted,
                    tmdbId = match.id,
                    totalSeasons = series.totalSeasons,
                    totalEpisodes = series.totalEpisodes,
                )
            }.onFailure { e ->
                Log.w(TAG, "Search failed for '${series.seriesTitle}': ${e.message}")
            }
            tracker.advance()
            delay(PACE_MS)
        }
    }

    // -----------------------------------------------------------------
    // Pass 2 — detail: plot, backdrop, cast, certification, etc.
    // -----------------------------------------------------------------

    private suspend fun detailPassAll() {
        while (true) {
            val pending = seriesDao.needingDetailEnrichment(limit = 50)
            if (pending.isEmpty()) break
            detailBatch(pending)
        }
    }

    private suspend fun detailBatch(pending: List<SeriesEntity>) {
        for (series in pending) {
            runCatching {
                val detail = tmdb.tvDetail(
                    id = series.tmdbId,
                    apiKey = AppConfig.TMDB_API_KEY,
                )
                seriesDao.updateDetail(
                    title = series.seriesTitle,
                    plot = detail.overview.orEmpty(),
                    backdropUrl = detail.backdropPath?.let {
                        "${AppConfig.TMDB_IMAGE_BASE}/${AppConfig.TMDB_BACKDROP_SIZE}$it"
                    } ?: "",
                    cast = MovieEnrichmentService.formatCast(detail.credits),
                    certification = pickUsTvRating(detail.contentRatings),
                    genre = detail.genres
                        .takeIf { it.isNotEmpty() }
                        ?.joinToString(", ") { it.name }
                        ?: "",
                    firstAirYear = detail.firstAirDate
                        ?.takeIf { it.length >= 4 }
                        ?.substring(0, 4)
                        ?.toIntOrNull(),
                )
            }.onFailure { e ->
                Log.w(TAG, "Detail failed for tmdbId=${series.tmdbId} " +
                    "'${series.seriesTitle}': ${e.message}")
            }
            tracker.advance()
            delay(PACE_MS)
        }
    }

    // -----------------------------------------------------------------
    // Pass 3 — episode names: fetch season detail from TMDB
    // -----------------------------------------------------------------

    private suspend fun episodeNamePassAll() {
        while (true) {
            val pending = seriesDao.needingEpisodeNameEnrichment(limit = 50)
            if (pending.isEmpty()) break
            episodeNameBatch(pending)
        }
    }

    private suspend fun episodeNameBatch(pending: List<SeriesEntity>) {
        for (series in pending) {
            val seasons = if (series.totalSeasons > 0) series.totalSeasons else 1
            for (seasonNum in 1..seasons) {
                runCatching {
                    val seasonDetail = tmdb.tvSeason(
                        id = series.tmdbId,
                        season = seasonNum,
                        apiKey = AppConfig.TMDB_API_KEY,
                    )
                    for (ep in seasonDetail.episodes) {
                        if (ep.name.isNotBlank()) {
                            episodeDao.updateName(
                                series = series.seriesTitle,
                                season = seasonNum,
                                episode = ep.episodeNumber,
                                title = ep.name,
                            )
                        }
                    }
                }.onFailure { e ->
                    Log.w(TAG, "Episode names failed for '${series.seriesTitle}' " +
                        "S${seasonNum}: ${e.message}")
                }
                delay(PACE_MS)
            }
            tracker.advance()
        }
    }

    // -----------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------

    /** TMDB TV genre ID to display name. */
    private fun tvGenreName(id: Int): String = when (id) {
        10759 -> "Action & Adventure"; 16 -> "Animation"
        35 -> "Comedy"; 80 -> "Crime"; 99 -> "Documentary"
        18 -> "Drama"; 10751 -> "Family"; 10762 -> "Kids"
        9648 -> "Mystery"; 10763 -> "News"; 10764 -> "Reality"
        10765 -> "Sci-Fi & Fantasy"; 10766 -> "Soap"
        10767 -> "Talk"; 10768 -> "War & Politics"
        37 -> "Western"; else -> "Other"
    }

    companion object {
        private const val TAG = "SeriesEnrichment"
        private const val PACE_MS = 50L

        /**
         * Extracts the US content rating from TMDB's `content_ratings`
         * wrapper.  Falls back to empty string when there's no US entry.
         */
        fun pickUsTvRating(wrapper: TmdbContentRatingsWrapper?): String =
            wrapper?.results
                ?.firstOrNull { it.iso == "US" }
                ?.rating
                ?: ""
    }
}
