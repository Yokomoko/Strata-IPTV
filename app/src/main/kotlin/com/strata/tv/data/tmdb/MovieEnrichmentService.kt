package com.strata.tv.data.tmdb

import android.util.Log
import com.strata.tv.AppConfig
import com.strata.tv.data.db.MovieDao
import com.strata.tv.data.db.MovieEntity
import com.strata.tv.data.settings.SettingsRepository
import com.strata.tv.domain.TitleParser
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Combined TMDB enrichment for movies.
 *
 * Each movie goes through: search → detail (with credits, release_dates,
 * watch/providers all in one request via append_to_response).  That's
 * 2 API calls per movie max, with 4 concurrent requests to maximize
 * throughput within TMDB's 40 req/10s rate limit.
 */
@Singleton
class MovieEnrichmentService @Inject constructor(
    private val tmdb: TmdbApi,
    private val movieDao: MovieDao,
    private val tracker: EnrichmentProgressTracker,
    private val settingsRepo: SettingsRepository,
) {
    /** Enrich all movies that need it. */
    suspend fun enrichBatch() {
        val totalNeeding = movieDao.countNeedingEnrichment()
        tracker.addWork(totalNeeding)

        while (true) {
            val batch = movieDao.needingEnrichment(limit = 100)
            if (batch.isEmpty()) break
            processBatchParallel(batch)
        }
    }

    /**
     * Process a batch with up to 4 concurrent requests.
     * TMDB allows 40 req/10s ≈ 4 req/s. With 4 parallel workers
     * each paced at ~300ms, we stay within limits.
     */
    private suspend fun processBatchParallel(batch: List<MovieEntity>) = coroutineScope {
        val semaphore = Semaphore(CONCURRENCY)
        batch.map { movie ->
            async {
                semaphore.withPermit {
                    enrichSingle(movie)
                    tracker.advance()
                    delay(PACE_MS)
                }
            }
        }.awaitAll()
    }

    /**
     * Full enrichment for one movie: search → detail (if search found a match).
     * 2 API calls, all metadata in one shot.
     */
    private suspend fun enrichSingle(movie: MovieEntity) {
        try {
            var tmdbId = movie.tmdbId
            // Snapshot the language + genre filters for this call so the
            // rules are consistent for both Search and Detail passes.
            val settings = settingsRepo.current()
            val wantedLanguages = settings.wantedLanguages
            val excludedLanguages = settings.excludedLanguages
            val excludedGenres = settings.excludedGenres
            val minimumYear = settings.minimumYear

            // Step 1: Search for TMDB ID (skip if already known)
            if (tmdbId == 0) {
                val cleaned = TitleParser.cleanForSearch(movie.movieTitle)
                val response = tmdb.searchMovie(
                    apiKey = AppConfig.TMDB_API_KEY,
                    query = cleaned.title,
                    year = movie.year ?: cleaned.year,
                )
                // Pick the best title-matching result, not blindly the
                // first.  TMDB sorts by popularity, so a generic query
                // like "The Stranger" with no year was returning
                // "Pirates of the Caribbean" (which is hugely popular
                // and apparently fuzzy-matches enough to be #1).
                // pickBestMatch requires the candidate's title to
                // actually contain the query — better no poster than
                // someone else's poster.
                val match = pickBestMatch(
                    candidates = response.results,
                    query = cleaned.title,
                    year = movie.year ?: cleaned.year,
                ) ?: run {
                    Log.d(TAG, "No good TMDB match for '${movie.movieTitle}' (query='${cleaned.title}')")
                    return
                }
                val lang = match.originalLanguage.orEmpty()
                val genreStr = match.genreIds.joinToString(", ") { genreName(it) }
                val tmdbYear = match.releaseDate?.take(4)?.toIntOrNull()
                val isLangWanted = wantedLanguages.isEmpty() || lang in wantedLanguages
                val isLangExcluded = lang in excludedLanguages
                val isGenreExcluded = excludedGenres.any { g ->
                    g.isNotEmpty() && genreStr.contains(g, ignoreCase = true)
                }
                val tooOld = minimumYear > 0 &&
                    tmdbYear != null && tmdbYear < minimumYear
                val isHidden = !isLangWanted || isLangExcluded || isGenreExcluded || tooOld

                movieDao.updateMetadata(
                    contentId = movie.contentId,
                    poster = match.posterPath?.let {
                        "${AppConfig.TMDB_IMAGE_BASE}/${AppConfig.TMDB_POSTER_SIZE}$it"
                    } ?: "",
                    genre = genreStr,
                    rating = match.voteAverage,
                    language = lang,
                    hidden = isHidden,
                    tmdbId = match.id,
                )

                if (isHidden) return
                tmdbId = match.id
                delay(PACE_MS)
            }

            // Step 2: Detail call — overview, backdrop, cast, cert, provider
            val detail = tmdb.movieDetail(
                id = tmdbId,
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

            // Provider from the same detail call (no extra API request!)
            val provider = pickProvider(detail.watchProviders)
            if (provider.isNotBlank()) {
                movieDao.updateProvider(movie.contentId, provider)
            }

            // Trailer URL from the same detail call
            val trailerKey = pickTrailerKey(detail.videos)
            if (trailerKey != null) {
                movieDao.updateTrailerUrl(
                    movie.contentId,
                    "https://www.youtube.com/watch?v=$trailerKey",
                )
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Enrich failed for '${movie.movieTitle}': ${e.message}")
        }
    }

    // -----------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------

    /**
     * Pick the best matching TMDB result for our search query.
     *
     * TMDB sorts results by popularity by default, so for short / generic
     * queries (e.g. "The Stranger", "Frankenstein", "Wonder") the most
     * popular movie that *vaguely* matches floats to the top — even if
     * its title has nothing to do with our actual film.  This was
     * surfacing "Pirates of the Caribbean: On Stranger Tides" as the
     * art for a movie called "The Stranger".
     *
     * Scoring (higher = better):
     *  - title equals query (normalised): +1000
     *  - originalTitle equals query: +800
     *  - title startsWith query: +200
     *  - title contains query as a whole word: +50
     *  - release year matches our year: +30
     *
     * Anything scoring 0 (no title overlap at all) is dropped — we'd
     * rather leave a movie with no poster than slap "Pirates" on it.
     */
    private fun pickBestMatch(
        candidates: List<TmdbMovie>,
        query: String,
        year: Int?,
    ): TmdbMovie? {
        if (candidates.isEmpty()) return null
        val q = TitleParser.normalise(query)
        if (q.isBlank()) return candidates.firstOrNull()

        var bestScore = 0
        var best: TmdbMovie? = null
        for (c in candidates) {
            val t = TitleParser.normalise(c.title)
            val o = TitleParser.normalise(c.originalTitle.orEmpty())
            var score = 0
            when {
                t == q -> score += 1000
                o.isNotBlank() && o == q -> score += 800
                t.startsWith("$q ") || t.startsWith("$q:") -> score += 200
                t.startsWith(q) -> score += 150
                " $q " in " $t " -> score += 50
                q in t -> score += 25
            }
            if (year != null && c.releaseDate?.take(4)?.toIntOrNull() == year) {
                score += 30
            }
            if (score > bestScore) {
                bestScore = score
                best = c
            }
        }
        return best
    }

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
        private const val PACE_MS = 80L
        private const val CONCURRENCY = 4

        /** Languages the user wants to keep — English or unspecified. */
        val WANTED_LANGUAGES = setOf("en", "")

        fun formatCast(credits: TmdbCredits?): String =
            credits?.cast
                ?.sortedBy { it.order }
                ?.take(5)
                ?.joinToString(", ") { it.name }
                ?: ""

        fun pickUsCertification(wrapper: TmdbReleaseDatesWrapper?): String =
            wrapper?.results
                ?.firstOrNull { it.iso == "US" }
                ?.releaseDates
                ?.firstOrNull { it.certification.isNotBlank() }
                ?.certification
                ?: ""

        fun pickProvider(wrapper: TmdbWatchProvidersWrapper?): String {
            val results = wrapper?.results ?: return ""
            val country = results["GB"] ?: results["US"] ?: return ""
            return country.flatrate.firstOrNull()?.providerName ?: ""
        }

        fun pickTrailerKey(wrapper: TmdbVideosWrapper?): String? {
            val videos = wrapper?.results?.filter {
                it.site == "YouTube" && it.type == "Trailer"
            } ?: return null
            // Prefer official trailers
            return (videos.firstOrNull { it.official } ?: videos.firstOrNull())?.key
        }
    }
}
