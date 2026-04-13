package com.strata.tv.data.tmdb

import com.strata.tv.AppConfig
import com.strata.tv.data.db.MovieDao
import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hits TMDB's search endpoint for every movie that hasn't yet been
 * enriched, persists poster + rating + language + tmdbId.
 *
 * v1 ran this with quite an aggressive batch size (15 concurrent
 * requests every 6s).  v2 starts gentler — sequential with a short
 * delay — because we have no playback yet to compete with.  A future
 * commit moves this to WorkManager and ramps the batch size up.
 *
 * Parity rule with v1: if TMDB's `original_language` isn't English we
 * mark the movie hidden=true so it falls out of all listings.  The
 * user's library is mostly English-language; non-English titles are
 * usually mis-tagged junk that pollutes the rails.
 */
@Singleton
class MovieEnrichmentService @Inject constructor(
    private val tmdb: TmdbApi,
    private val movieDao: MovieDao,
) {

    suspend fun enrichBatch() {
        val pending = movieDao.needingEnrichment(limit = 100)
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
            }
            // Light pacing — single-request loop won't trip TMDB's
            // 40-req/10s ceiling but we'd rather not run flat-out.
            delay(150)
        }
    }

    /** TMDB genre ID → display name. */
    private fun genreName(id: Int): String = when (id) {
        28 -> "Action"; 12 -> "Adventure"; 16 -> "Animation"
        35 -> "Comedy"; 80 -> "Crime"; 99 -> "Documentary"
        18 -> "Drama"; 10751 -> "Family"; 14 -> "Fantasy"
        36 -> "History"; 27 -> "Horror"; 10402 -> "Music"
        9648 -> "Mystery"; 10749 -> "Romance"; 878 -> "Sci-Fi"
        10770 -> "TV Movie"; 53 -> "Thriller"; 10752 -> "War"
        37 -> "Western"; else -> "Other"
    }
}
