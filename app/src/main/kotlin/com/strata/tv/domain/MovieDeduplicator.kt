package com.strata.tv.domain

import android.util.Log
import com.strata.tv.data.db.ContentDao
import com.strata.tv.data.db.EpisodeDao
import com.strata.tv.data.db.MovieDao
import com.strata.tv.data.db.MovieEntity
import com.strata.tv.data.db.SeriesDao
import com.strata.tv.data.db.SeriesEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Deduplicates movies that appear multiple times in the M3U playlist
 * under different quality variants (HD, FHD, HEVC, 4K, etc.).
 *
 * Each variant arrives with a different `content_id` (because the
 * stream URL differs) and a display name like `"FHD : Movie Title 2025"`
 * or `"HEVC FHD : Movie Title 2025"`.  Without dedup they all show up
 * as separate cards in the UI rails and each wastes a TMDB enrichment
 * call.
 *
 * The algorithm:
 *   1. Fetch all movies (including hidden ones that were hidden by a
 *      prior dedup pass).
 *   2. For each movie, look up its [ContentItemEntity.displayName] and
 *      extract the quality tier from the prefix / URL.
 *   3. Group movies by normalised title (via [TitleParser.normalise]).
 *   4. In each group with >1 entry, pick the highest quality variant
 *      as the "winner" and hide all others.
 *   5. Preserve the winner's enrichment data — if the winner has no
 *      poster but a sibling does, prefer the enriched sibling as winner.
 *
 * Stream validation runs as a separate [validateWinners] pass. It
 * HEAD-checks the winning variant's URL; if it is dead the next-best
 * variant is promoted.
 */
@Singleton
class MovieDeduplicator @Inject constructor(
    private val movieDao: MovieDao,
    private val contentDao: ContentDao,
    private val seriesDao: SeriesDao,
    private val episodeDao: EpisodeDao,
    private val http: OkHttpClient,
) {

    /**
     * Quality tier ordering.  Higher ordinal = higher quality.
     * Matches the provider's naming conventions observed in the M3U.
     */
    enum class Quality {
        Unknown,
        Sd,
        Hd,
        HevcHd,
        Fhd,
        HevcFhd,
        Uhd4K,
    }

    /**
     * Run the full deduplication pass.
     *
     * Safe to call repeatedly — it is idempotent.  Hidden variants
     * that are no longer duplicates (e.g. after a playlist change)
     * will be unhidden automatically.
     *
     * @return the number of variants that were hidden.
     */
    suspend fun dedup(): Int = withContext(Dispatchers.IO) {
        val allMovies = movieDao.allIncludingHidden()
        if (allMovies.isEmpty()) return@withContext 0

        // Build a map of contentId -> displayName for quality detection.
        val displayNames = mutableMapOf<String, String>()
        val streamUrls = mutableMapOf<String, String>()
        for (movie in allMovies) {
            val content = contentDao.byContentId(movie.contentId)
            if (content != null) {
                displayNames[movie.contentId] = content.displayName
                streamUrls[movie.contentId] = content.streamUrl
            }
        }

        // Group movies by normalised title.
        val groups = allMovies.groupBy { movie ->
            TitleParser.normalise(movie.movieTitle)
        }

        var hiddenCount = 0

        for ((normTitle, variants) in groups) {
            if (variants.size <= 1) {
                // No duplicates -- ensure the single entry is visible
                // (it may have been hidden by a prior dedup run that
                // grouped differently).
                val only = variants.first()
                if (only.hidden) {
                    movieDao.setHidden(only.id, hidden = false)
                }
                continue
            }

            // Score each variant.
            val scored = variants.map { movie ->
                val dn = displayNames[movie.contentId].orEmpty()
                val url = streamUrls[movie.contentId].orEmpty()
                val quality = detectQuality(dn, url)
                ScoredMovie(movie, quality, dn)
            }

            // Pick the winner: highest quality, then prefer an enriched
            // variant (has poster) over an unenriched one at the same
            // quality level, then prefer lower ID (first synced).
            val winner = scored
                .sortedWith(
                    compareByDescending<ScoredMovie> { it.quality.ordinal }
                        .thenByDescending { it.movie.posterUrl.isNotEmpty() }
                        .thenBy { it.movie.id },
                )
                .first()

            for (sm in scored) {
                val shouldHide = sm.movie.id != winner.movie.id
                if (sm.movie.hidden != shouldHide) {
                    movieDao.setHidden(sm.movie.id, hidden = shouldHide)
                }
                if (shouldHide) hiddenCount++
            }

            Log.d(
                TAG,
                "Dedup '$normTitle': ${variants.size} variants, " +
                    "winner=${winner.quality.name} (id=${winner.movie.id}), " +
                    "hidden ${variants.size - 1}",
            )
        }

        Log.i(TAG, "Dedup complete: $hiddenCount variants hidden across ${groups.size} titles")
        hiddenCount
    }

    /**
     * Validate that each dedup winner's stream URL is still alive.
     *
     * For every normalised-title group where the visible (winner) movie
     * has a dead stream, hide it and promote the next-best variant.
     * This is heavier than [dedup] (HTTP HEAD per winner) and should
     * run less frequently -- e.g. once per day or on explicit refresh.
     *
     * @return the number of winners that were replaced.
     */
    suspend fun validateWinners(): Int = withContext(Dispatchers.IO) {
        val allMovies = movieDao.allIncludingHidden()
        if (allMovies.isEmpty()) return@withContext 0

        // Build display-name and stream-url lookups.
        val displayNames = mutableMapOf<String, String>()
        val streamUrls = mutableMapOf<String, String>()
        for (movie in allMovies) {
            val content = contentDao.byContentId(movie.contentId)
            if (content != null) {
                displayNames[movie.contentId] = content.displayName
                streamUrls[movie.contentId] = content.streamUrl
            }
        }

        val groups = allMovies.groupBy { TitleParser.normalise(it.movieTitle) }
        var replacedCount = 0

        for ((_, variants) in groups) {
            if (variants.size <= 1) continue

            val visible = variants.filter { !it.hidden }
            if (visible.isEmpty()) continue

            val winner = visible.first()
            val url = streamUrls[winner.contentId] ?: continue

            val alive = isStreamAlive(url)
            if (alive) continue

            Log.w(TAG, "Dead stream for winner id=${winner.id}: $url")

            // Hide the dead winner.
            movieDao.setHidden(winner.id, hidden = true)

            // Find the next-best variant among the hidden ones.
            val candidates = variants
                .filter { it.id != winner.id }
                .map { movie ->
                    val dn = displayNames[movie.contentId].orEmpty()
                    val su = streamUrls[movie.contentId].orEmpty()
                    ScoredMovie(movie, detectQuality(dn, su), dn)
                }
                .sortedWith(
                    compareByDescending<ScoredMovie> { it.quality.ordinal }
                        .thenByDescending { it.movie.posterUrl.isNotEmpty() }
                        .thenBy { it.movie.id },
                )

            val replacement = candidates.firstOrNull()
            if (replacement != null) {
                movieDao.setHidden(replacement.movie.id, hidden = false)
                Log.i(
                    TAG,
                    "Promoted variant id=${replacement.movie.id} " +
                        "(${replacement.quality.name}) for '${winner.movieTitle}'",
                )
            }
            replacedCount++
        }

        Log.i(TAG, "Validation complete: $replacedCount winners replaced")
        replacedCount
    }

    // -------------------------------------------------------------------------
    // Series deduplication
    // -------------------------------------------------------------------------

    /**
     * Deduplicate TV series that appear multiple times under different
     * quality variants (HD, FHD, HEVC, etc.).
     *
     * The series table itself does not carry a quality prefix, so we
     * sample a representative episode from each series and inspect its
     * [ContentItemEntity.displayName] for quality keywords.
     *
     * Safe to call repeatedly — idempotent like [dedup].
     *
     * @return the number of series variants that were hidden.
     */
    suspend fun dedupSeries(): Int = withContext(Dispatchers.IO) {
        val allSeries = seriesDao.allIncludingHidden()
        if (allSeries.isEmpty()) return@withContext 0

        // Group series by normalised title.
        val groups = allSeries.groupBy { series ->
            TitleParser.normalise(series.seriesTitle)
        }

        var hiddenCount = 0

        for ((normTitle, variants) in groups) {
            if (variants.size <= 1) {
                // No duplicates — ensure the single entry is visible.
                val only = variants.first()
                if (only.hidden) {
                    seriesDao.setHidden(only.seriesTitle, hidden = false)
                }
                continue
            }

            // Score each variant by looking up a sample episode's display name.
            val scored = variants.map { series ->
                val episode = episodeDao.firstOf(series.seriesTitle)
                val displayName = if (episode != null) {
                    contentDao.byContentId(episode.contentId)?.displayName.orEmpty()
                } else ""
                val quality = detectQuality(displayName)
                ScoredSeries(series, quality, displayName)
            }

            // Pick the winner: highest quality, then prefer an enriched
            // variant (has poster) over an unenriched one, then lower ID.
            val winner = scored
                .sortedWith(
                    compareByDescending<ScoredSeries> { it.quality.ordinal }
                        .thenByDescending { it.series.posterUrl.isNotEmpty() }
                        .thenBy { it.series.id },
                )
                .first()

            for (sm in scored) {
                val shouldHide = sm.series.id != winner.series.id
                if (sm.series.hidden != shouldHide) {
                    seriesDao.setHidden(sm.series.seriesTitle, hidden = shouldHide)
                }
                if (shouldHide) hiddenCount++
            }

            Log.d(
                TAG,
                "Dedup series '$normTitle': ${variants.size} variants, " +
                    "winner=${winner.quality.name} (id=${winner.series.id}), " +
                    "hidden ${variants.size - 1}",
            )
        }

        Log.i(TAG, "Series dedup complete: $hiddenCount variants hidden across ${groups.size} titles")
        hiddenCount
    }

    // -------------------------------------------------------------------------
    // Quality detection
    // -------------------------------------------------------------------------

    /**
     * Detect the quality tier from a display name and/or stream URL.
     *
     * The display name is the primary signal -- the provider prefixes
     * quality tags like `"HEVC FHD : "`, `"FHD : "`, `"HD : "`.  The
     * stream URL is a fallback for providers that encode quality in the
     * URL path (e.g. `/4k/` or `/hevc/`).
     */
    internal fun detectQuality(displayName: String, streamUrl: String = ""): Quality {
        val dn = displayName.uppercase()

        // Check the prefix (text before the first " : " separator).
        val prefix = dn.substringBefore(" : ").let { if (it == dn) "" else it }

        // 4K / UHD -- highest priority.
        if ("4K" in prefix || "UHD" in prefix) return Quality.Uhd4K
        // HEVC + FHD in prefix.
        if ("HEVC" in prefix && "FHD" in prefix) return Quality.HevcFhd
        // FHD without HEVC.
        if ("FHD" in prefix) return Quality.Fhd
        // HEVC + HD (but not FHD).
        if ("HEVC" in prefix && "HD" in prefix) return Quality.HevcHd
        // HD in prefix (covers "HD", "UKHD", "NEW UKHD", etc.).
        if ("HD" in prefix) return Quality.Hd
        // SD in prefix.
        if ("SD" in prefix) return Quality.Sd

        // Fallback: check the full display name for keywords anywhere.
        if ("4K" in dn || "UHD" in dn) return Quality.Uhd4K
        if ("HEVC" in dn && "FHD" in dn) return Quality.HevcFhd
        if ("FHD" in dn) return Quality.Fhd
        if ("HEVC" in dn && "HD" in dn) return Quality.HevcHd
        if ("HEVC" in dn) return Quality.HevcFhd  // HEVC alone implies FHD quality
        if ("HD" in dn) return Quality.Hd
        if ("SD" in dn) return Quality.Sd

        // Fallback: check the stream URL path.
        val urlUpper = streamUrl.uppercase()
        if ("/4K/" in urlUpper || "/UHD/" in urlUpper) return Quality.Uhd4K
        if ("/HEVC/" in urlUpper && "/FHD/" in urlUpper) return Quality.HevcFhd
        if ("/HEVC/" in urlUpper) return Quality.HevcFhd
        if ("/FHD/" in urlUpper) return Quality.Fhd
        if ("/HD/" in urlUpper) return Quality.Hd
        if ("/SD/" in urlUpper) return Quality.Sd

        return Quality.Unknown
    }

    // -------------------------------------------------------------------------
    // Stream health check
    // -------------------------------------------------------------------------

    /**
     * HEAD-check a stream URL.  Returns true if the server responds
     * with a 2xx status within a tight timeout.
     */
    private fun isStreamAlive(url: String): Boolean {
        return try {
            val request = Request.Builder()
                .url(url)
                .head()
                .build()
            http.newCall(request).execute().use { it.isSuccessful }
        } catch (_: Throwable) {
            false
        }
    }

    // -------------------------------------------------------------------------
    // Internal types
    // -------------------------------------------------------------------------

    private data class ScoredMovie(
        val movie: MovieEntity,
        val quality: Quality,
        val displayName: String,
    )

    private data class ScoredSeries(
        val series: SeriesEntity,
        val quality: Quality,
        val displayName: String,
    )

    companion object {
        private const val TAG = "MovieDedup"
    }
}
