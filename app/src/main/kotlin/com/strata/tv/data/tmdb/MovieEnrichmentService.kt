package com.strata.tv.data.tmdb

import android.util.Log
import com.strata.tv.AppConfig
import com.strata.tv.data.db.MovieDao
import com.strata.tv.data.db.MovieEntity
import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Two-pass TMDB enrichment for movies:
 *
 * 1. **Search pass** — finds movies missing poster / genre / rating,
 *    calls `/search/movie`, persists the basics + tmdbId.
 * 2. **Detail pass** — for movies that have a tmdbId but are still
 *    missing overview or backdrop, calls `/movie/{id}` with
 *    `append_to_response=credits,release_dates` and persists the
 *    richer metadata (overview, backdrop, cast, certification, runtime,
 *    year and genres when those were missing).
 *
 * Pacing: 200 ms between requests (doubled call volume vs Phase 7's
 * single-request loop), well under TMDB's 40-req/10s ceiling.
 *
 * Parity rule with v1: if TMDB's `original_language` isn't English we
 * mark the movie hidden=true so it falls out of all listings.
 */
@Singleton
class MovieEnrichmentService @Inject constructor(
    private val tmdb: TmdbApi,
    private val movieDao: MovieDao,
    private val tracker: EnrichmentProgressTracker,
) {

    /** Run all enrichment passes, looping until all items are done. */
    suspend fun enrichBatch() {
        // Set total up-front so the progress ring doesn't oscillate.
        val totalNeeding = movieDao.countNeedingEnrichment()
        tracker.addWork(totalNeeding)
        searchPassAll()
        detailPassAll()
        providerPassAll()
    }

    // -----------------------------------------------------------------
    // Pass 1 — search: poster, genre IDs, rating, language, tmdbId
    // -----------------------------------------------------------------

    private suspend fun searchPassAll() {
        while (true) {
            val pending = movieDao.needingEnrichment(limit = 50)
            if (pending.isEmpty()) break
            searchBatch(pending)
        }
    }

    private suspend fun searchBatch(pending: List<MovieEntity>) {
        for (movie in pending) {
            runCatching {
                val response = tmdb.searchMovie(
                    apiKey = AppConfig.TMDB_API_KEY,
                    query = movie.movieTitle,
                    year = movie.year,
                )
                val match = response.results.firstOrNull() ?: return@runCatching
                val isEnglish = match.originalLanguage.isNullOrEmpty() ||
                    match.originalLanguage == "en"
                movieDao.updateMetadata(
                    contentId = movie.contentId,
                    poster = match.posterPath?.let {
                        "${AppConfig.TMDB_IMAGE_BASE}/${AppConfig.TMDB_POSTER_SIZE}$it"
                    } ?: "",
                    genre = match.genreIds.joinToString(", ") { genreName(it) },
                    rating = match.voteAverage,
                    language = match.originalLanguage.orEmpty(),
                    hidden = !isEnglish,
                    tmdbId = match.id,
                )
            }.onFailure { e ->
                Log.w(TAG, "Search failed for '${movie.movieTitle}': ${e.message}")
            }
            tracker.advance()
            delay(PACE_MS)
        }
    }

    // -----------------------------------------------------------------
    // Pass 2 — detail: overview, backdrop, cast, certification, etc.
    // -----------------------------------------------------------------

    private suspend fun detailPassAll() {
        while (true) {
            val pending = movieDao.needingDetailEnrichment(limit = 50)
            if (pending.isEmpty()) break
            detailBatch(pending)
        }
    }

    private suspend fun detailBatch(pending: List<MovieEntity>) {
        for (movie in pending) {
            runCatching {
                val detail = tmdb.movieDetail(
                    id = movie.tmdbId,
                    apiKey = AppConfig.TMDB_API_KEY,
                )
                movieDao.updateDetail(
                    contentId = movie.contentId,
                    overview = detail.overview.orEmpty(),
                    backdropUrl = detail.backdropPath?.let {
                        "${AppConfig.TMDB_IMAGE_BASE}/${AppConfig.TMDB_BACKDROP_SIZE}$it"
                    } ?: "",
                    cast = formatCast(detail.credits),
                    certification = pickUsCertification(detail.releaseDates),
                    runtime = detail.runtime,
                    genre = detail.genres
                        .takeIf { it.isNotEmpty() }
                        ?.joinToString(", ") { it.name }
                        ?: "",
                    year = detail.releaseDate
                        ?.takeIf { it.length >= 4 }
                        ?.substring(0, 4)
                        ?.toIntOrNull(),
                )
                // Also set provider if watch/providers returned data.
                val provider = pickProvider(detail.watchProviders)
                if (provider.isNotBlank()) {
                    movieDao.updateProvider(movie.contentId, provider)
                }
            }.onFailure { e ->
                // Search succeeded earlier — don't lose poster data.
                // Just log and continue to the next title.
                Log.w(TAG, "Detail failed for tmdbId=${movie.tmdbId} " +
                    "'${movie.movieTitle}': ${e.message}")
            }
            tracker.advance()
            delay(PACE_MS)
        }
    }

    // -----------------------------------------------------------------
    // Pass 3 — provider: streaming service from watch/providers
    // -----------------------------------------------------------------

    private suspend fun providerPassAll() {
        while (true) {
            val pending = movieDao.needingProviderLookup(limit = 50)
            if (pending.isEmpty()) break
            tracker.addWork(pending.size)
            for (movie in pending) {
                runCatching {
                    val detail = tmdb.movieDetail(
                        id = movie.tmdbId,
                        apiKey = AppConfig.TMDB_API_KEY,
                        append = "watch/providers",
                    )
                    val provider = pickProvider(detail.watchProviders)
                    if (provider.isNotBlank()) {
                        movieDao.updateProvider(movie.contentId, provider)
                    } else {
                        // Mark as "Unknown" so we don't re-query.
                        movieDao.updateProvider(movie.contentId, "Unknown")
                    }
                }.onFailure { e ->
                    Log.w(TAG, "Provider lookup failed for tmdbId=${movie.tmdbId}: ${e.message}")
                }
                tracker.advance()
                delay(PACE_MS)
            }
        }
    }

    // -----------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------

    /** TMDB genre ID → display name (used by the search pass). */
    private fun genreName(id: Int): String = when (id) {
        28 -> "Action"; 12 -> "Adventure"; 16 -> "Animation"
        35 -> "Comedy"; 80 -> "Crime"; 99 -> "Documentary"
        18 -> "Drama"; 10751 -> "Family"; 14 -> "Fantasy"
        36 -> "History"; 27 -> "Horror"; 10402 -> "Music"
        9648 -> "Mystery"; 10749 -> "Romance"; 878 -> "Sci-Fi"
        10770 -> "TV Movie"; 53 -> "Thriller"; 10752 -> "War"
        37 -> "Western"; else -> "Other"
    }

    companion object {
        private const val TAG = "MovieEnrichment"
        private const val PACE_MS = 200L

        /** Top-5 cast names, comma-separated. */
        fun formatCast(credits: TmdbCredits?): String =
            credits?.cast
                ?.sortedBy { it.order }
                ?.take(5)
                ?.joinToString(", ") { it.name }
                ?: ""

        /**
         * Extracts the US certification from TMDB's `release_dates`
         * wrapper.  Falls back to empty string when there's no US entry.
         */
        /**
         * Extracts the first flatrate streaming provider, preferring GB
         * over US.  Returns the provider name (e.g. "Netflix") or "".
         */
        fun pickProvider(wrapper: TmdbWatchProvidersWrapper?): String {
            val results = wrapper?.results ?: return ""
            // Prefer GB, fall back to US.
            val country = results["GB"] ?: results["US"] ?: return ""
            return country.flatrate.firstOrNull()?.providerName ?: ""
        }

        fun pickUsCertification(wrapper: TmdbReleaseDatesWrapper?): String =
            wrapper?.results
                ?.firstOrNull { it.iso == "US" }
                ?.releaseDates
                ?.firstOrNull { it.certification.isNotBlank() }
                ?.certification
                ?: ""
    }
}
