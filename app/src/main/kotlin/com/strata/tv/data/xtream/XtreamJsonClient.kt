package com.strata.tv.data.xtream

import android.util.Log
import com.strata.tv.data.m3u.M3uEntry
import com.strata.tv.domain.ContentType
import com.strata.tv.domain.MovieDeduplicator
import com.strata.tv.domain.TitleParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pulls the Xtream Codes JSON API (`player_api.php`) and converts the
 * responses into [M3uEntry] objects so the existing
 * [com.strata.tv.data.repo.SyncService] persistence pipeline can write
 * them to Room unchanged.
 *
 * Used as a fallback when `get.php?type=m3u_plus` returns JSON or an
 * empty body — some providers (e.g. MyBunny.TV) don't serve M3U at all,
 * only the JSON API.  The JSON path is universal across Xtream Codes
 * deployments so it doubles as the primary path for any provider that
 * isn't a raw M3U URL.
 *
 * Live channel stream URLs come straight from the `direct_source` field
 * when present (which CDN-routes through the provider's edge host).
 * Series episode URLs are synthesised because `get_series` doesn't
 * include them — done as `{host}/series/{user}/{pass}/{episode_id}.{ext}`
 * per the Xtream spec.
 */
@Singleton
class XtreamJsonClient @Inject constructor(
    private val http: OkHttpClient,
) {
    companion object {
        private const val TAG = "XtreamJsonClient"

        /** Default UA when the caller doesn't supply one (legacy paths). */
        private const val DEFAULT_USER_AGENT = "okhttp/4.12.0"

        // get_series_info is rate-limited hard by some providers
        // (MyBunny.TV returns HTTP 429 at >2 concurrent).  We no longer
        // call it during the initial sync — it's fetched lazily when
        // the user opens a series detail screen.
        private const val LAZY_FETCH_RETRY_AFTER_MS = 1_500L
        private const val LAZY_FETCH_MAX_RETRIES = 4
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Pulls categories + live + VOD + series + episode listings and
     * returns them already shaped as [M3uEntry] objects ready for the
     * existing dedup/persist passes.
     *
     * @param host Provider host with scheme, e.g. `https://mybunny.tv`.
     * @param user Xtream username.
     * @param pass Xtream password.
     * @param onProgress Called with a human-readable status string for
     *   the sync indicator.
     */
    suspend fun fetchAll(
        host: String,
        user: String,
        pass: String,
        userAgent: String = DEFAULT_USER_AGENT,
        onProgress: (String) -> Unit = {},
    ): XtreamSnapshot = withContext(Dispatchers.IO) {
        val base = host.trimEnd('/')

        onProgress("Loading categories")
        val liveCats = fetchAction<XtreamCategory>(base, user, pass, "get_live_categories", userAgent)
            .associate { it.id to it.name }
        val vodCats = fetchAction<XtreamCategory>(base, user, pass, "get_vod_categories", userAgent)
            .associate { it.id to it.name }
        val seriesCats = fetchAction<XtreamCategory>(base, user, pass, "get_series_categories", userAgent)
            .associate { it.id to it.name }
        Log.d(
            TAG,
            "Categories: ${liveCats.size} live, ${vodCats.size} VOD, ${seriesCats.size} series",
        )

        onProgress("Loading live channels")
        val liveDtos = fetchAction<XtreamLiveStream>(base, user, pass, "get_live_streams", userAgent)
        Log.d(TAG, "Fetched ${liveDtos.size} live streams")
        val liveEntries = liveDtos.mapNotNull { it.toEntry(base, user, pass, liveCats) }

        onProgress("Loading movies")
        val vodDtos = fetchAction<XtreamVodStream>(base, user, pass, "get_vod_streams", userAgent)
        Log.d(TAG, "Fetched ${vodDtos.size} VOD streams")
        val movieEntries = vodDtos.mapNotNull { it.toEntry(base, user, pass, vodCats) }

        onProgress("Loading box sets")
        val seriesDtos = fetchAction<XtreamSeries>(base, user, pass, "get_series", userAgent)
            // Some providers send duplicate entries for the same
            // series_id (different sources / qualities) — keep one each
            // so we don't double-write the series table.
            .distinctBy { it.seriesId }
        Log.d(TAG, "Fetched ${seriesDtos.size} unique series")

        // Episodes are no longer fetched up-front.  Hammering
        // get_series_info with 4000+ requests trips most providers'
        // rate limits (MyBunny.TV serves HTTP 429 above ~2 concurrent).
        // The series detail screen lazy-loads episodes when the user
        // opens a series, using [fetchEpisodesForSeries] below.
        val seriesEntries = seriesDtos.map { it.toSeriesEntry(seriesCats) }

        XtreamSnapshot(
            live = liveEntries,
            movies = movieEntries,
            seriesMeta = seriesEntries,
        )
    }

    /**
     * Lazy fetch of a single series' episodes.
     *
     * Called by the show detail screen the first time the user opens
     * a series that has no episodes in Room yet.  Retries on HTTP 429
     * with exponential backoff so the rare contended slot eventually
     * goes through without burning CPU.
     */
    suspend fun fetchEpisodesForSeries(
        host: String,
        user: String,
        pass: String,
        seriesId: Int,
        seriesTitle: String,
        groupTitle: String,
        userAgent: String = DEFAULT_USER_AGENT,
    ): List<M3uEntry> = withContext(Dispatchers.IO) {
        val base = host.trimEnd('/')
        val url = "$base/player_api.php?username=$user&password=$pass" +
            "&action=get_series_info&series_id=$seriesId"

        var attempt = 0
        while (true) {
            val request = Request.Builder().url(url).header("User-Agent", userAgent).build()
            try {
                http.newCall(request).execute().use { response ->
                    when {
                        response.code == 429 && attempt < LAZY_FETCH_MAX_RETRIES -> {
                            attempt++
                            // Provider doesn't always send Retry-After;
                            // back off exponentially so a busy spell
                            // doesn't keep failing.
                            val wait = LAZY_FETCH_RETRY_AFTER_MS shl (attempt - 1)
                            Log.w(TAG, "429 for series $seriesId, retry $attempt in ${wait}ms")
                            delay(wait)
                            return@use null
                        }
                        !response.isSuccessful -> {
                            Log.w(TAG, "HTTP ${response.code} for series $seriesId")
                            return@withContext emptyList()
                        }
                        else -> {
                            val body = response.body?.string().orEmpty()
                            if (body.isBlank()) return@withContext emptyList()
                            return@withContext parseSeriesInfo(
                                body = body,
                                base = base,
                                user = user,
                                pass = pass,
                                seriesId = seriesId,
                                seriesTitle = seriesTitle,
                                groupTitle = groupTitle,
                            )
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "fetch error for series $seriesId: ${t.message}")
                return@withContext emptyList()
            }
        }
        @Suppress("UNREACHABLE_CODE")
        emptyList()
    }

    private fun parseSeriesInfo(
        body: String,
        base: String,
        user: String,
        pass: String,
        seriesId: Int,
        seriesTitle: String,
        groupTitle: String,
    ): List<M3uEntry> {
        val info: XtreamSeriesInfo = json.decodeFromString(body)

        // Dedup by (season, episode_num) — providers commonly return
        // multiple variants per slot (4K + 1080p + 720p sources) and
        // we previously inserted all of them, giving the show detail
        // screen 3-4 cards of "Episode 1" for Season 5 of The Boys.
        // Keep the highest-quality variant per slot, falling back to
        // the last entry when no quality signal is present.
        data class SlotKey(val season: Int, val episode: Int)
        val bestPerSlot = mutableMapOf<SlotKey, XtreamEpisode>()
        for ((seasonKey, seasonEpisodes) in info.episodes.orEmpty()) {
            val season = seasonKey.toIntOrNull() ?: continue
            for (ep in seasonEpisodes) {
                val episodeNum = ep.episodeNum?.toIntOrNull() ?: continue
                val key = SlotKey(season, episodeNum)
                val existing = bestPerSlot[key]
                if (existing == null) {
                    bestPerSlot[key] = ep
                } else {
                    val existingQ = MovieDeduplicator.detectQuality(
                        existing.title.orEmpty(),
                        "$base/series/$user/$pass/${existing.id}.${existing.containerExtension.orEmpty()}",
                    ).ordinal
                    val newQ = MovieDeduplicator.detectQuality(
                        ep.title.orEmpty(),
                        "$base/series/$user/$pass/${ep.id}.${ep.containerExtension.orEmpty()}",
                    ).ordinal
                    if (newQ >= existingQ) bestPerSlot[key] = ep
                }
            }
        }

        return bestPerSlot.entries.map { (key, ep) ->
            val ext = ep.containerExtension?.takeIf { it.isNotBlank() } ?: "mp4"
            val streamUrl = "$base/series/$user/$pass/${ep.id}.$ext"
            val displayName = "$seriesTitle S%02dE%02d".format(key.season, key.episode)
            M3uEntry(
                displayName = displayName,
                streamUrl = streamUrl,
                groupTitle = groupTitle,
                tvgId = seriesId.toString(),
                tvgName = ep.title ?: displayName,
                tvgLogo = ep.info?.movieImage.orEmpty(),
                tvgType = "series",
                extinfDuration = 0,
                contentType = ContentType.Show,
                seriesTitle = seriesTitle,
                seasonNumber = key.season,
                episodeNumber = key.episode,
            )
        }
    }

    // ------------------------------------------------------------------
    // HTTP plumbing
    // ------------------------------------------------------------------

    private inline fun <reified T> fetchAction(
        base: String,
        user: String,
        pass: String,
        action: String,
        userAgent: String,
    ): List<T> {
        val url = "$base/player_api.php?username=$user&password=$pass&action=$action"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("HTTP ${response.code} fetching $action")
            }
            val body = response.body?.string() ?: error("Empty body for $action")
            if (body.isBlank() || body == "[]" || body == "null") return emptyList()
            return try {
                json.decodeFromString(body)
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to decode $action: ${e.message}; first 200 chars: ${body.take(200)}")
                throw e
            }
        }
    }

}

/**
 * Series catalogue entry — enough to populate the series table without
 * yet pulling the per-series episode listing.  The opaque
 * [xtreamSeriesId] gets persisted so [XtreamJsonClient.fetchEpisodesForSeries]
 * can be called on-demand from the show detail screen.
 */
data class XtreamSeriesMeta(
    val xtreamSeriesId: Int,
    val title: String,
    val groupTitle: String,
    val cover: String,
)

/** Output of [XtreamJsonClient.fetchAll] — already-classified entry lists. */
data class XtreamSnapshot(
    val live: List<M3uEntry>,
    val movies: List<M3uEntry>,
    val seriesMeta: List<XtreamSeriesMeta>,
)

// ----------------------------------------------------------------------
// DTOs
// ----------------------------------------------------------------------

@Serializable
internal data class XtreamCategory(
    @SerialName("category_id") private val categoryIdRaw: JsonElement? = null,
    @SerialName("category_name") private val categoryName: String? = null,
) {
    /** category_id is sometimes a string, sometimes an int. */
    val id: String get() = categoryIdRaw.asScalarString()
    val name: String get() = categoryName.orEmpty()
}

@Serializable
internal data class XtreamLiveStream(
    @SerialName("num") val num: Int? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("stream_type") val streamType: String? = null,
    @SerialName("stream_id") val streamId: JsonElement? = null,
    @SerialName("stream_icon") val streamIcon: String? = null,
    @SerialName("epg_channel_id") val epgChannelId: String? = null,
    @SerialName("category_id") val categoryId: JsonElement? = null,
    @SerialName("tv_archive") val tvArchive: Int? = null,
    @SerialName("direct_source") val directSource: String? = null,
) {
    fun toEntry(
        base: String,
        user: String,
        pass: String,
        categoryNames: Map<String, String>,
    ): M3uEntry? {
        val displayName = name?.trim().orEmpty()
        if (displayName.isEmpty()) return null
        val sid = streamId.asScalarString().ifBlank { return null }
        val url = directSource?.takeIf { it.isNotBlank() }
            ?: "$base/live/$user/$pass/$sid.ts"
        val groupTitle = categoryId.asScalarString().let { categoryNames[it] }.orEmpty()
        return M3uEntry(
            displayName = displayName,
            streamUrl = url,
            groupTitle = groupTitle,
            tvgId = epgChannelId.orEmpty(),
            tvgName = displayName,
            tvgLogo = streamIcon.orEmpty(),
            tvgType = "live",
            extinfDuration = -1,
            contentType = ContentType.Live,
        )
    }
}

@Serializable
internal data class XtreamVodStream(
    @SerialName("name") val name: String? = null,
    @SerialName("stream_id") val streamId: JsonElement? = null,
    @SerialName("stream_icon") val streamIcon: String? = null,
    @SerialName("category_id") val categoryId: JsonElement? = null,
    @SerialName("container_extension") val containerExtension: String? = null,
    @SerialName("direct_source") val directSource: String? = null,
    @SerialName("year") val year: String? = null,
) {
    fun toEntry(
        base: String,
        user: String,
        pass: String,
        categoryNames: Map<String, String>,
    ): M3uEntry? {
        val displayName = name?.trim().orEmpty()
        if (displayName.isEmpty()) return null
        val sid = streamId.asScalarString().ifBlank { return null }
        val ext = containerExtension?.takeIf { it.isNotBlank() } ?: "mp4"
        val url = directSource?.takeIf { it.isNotBlank() }
            ?: "$base/movie/$user/$pass/$sid.$ext"
        val groupTitle = categoryId.asScalarString().let { categoryNames[it] }.orEmpty()
        val parsed = TitleParser.parseMovie(displayName)
        return M3uEntry(
            displayName = displayName,
            streamUrl = url,
            groupTitle = groupTitle,
            tvgId = sid,
            tvgName = displayName,
            tvgLogo = streamIcon.orEmpty(),
            tvgType = "movie",
            extinfDuration = 0,
            contentType = ContentType.Movie,
            movieTitle = parsed?.title ?: TitleParser.stripHdPrefix(displayName),
            movieYear = parsed?.year ?: year?.toIntOrNull(),
        )
    }
}

@Serializable
internal data class XtreamSeries(
    @SerialName("series_id") val seriesId: Int = 0,
    @SerialName("name") val name: String = "",
    @SerialName("cover") val cover: String? = null,
    @SerialName("category_id") val categoryId: String? = null,
) {
    fun toSeriesEntry(categoryNames: Map<String, String>): XtreamSeriesMeta {
        val categoryName = categoryId?.let { categoryNames[it] }.orEmpty()
        return XtreamSeriesMeta(
            xtreamSeriesId = seriesId,
            title = TitleParser.stripHdPrefix(name),
            groupTitle = categoryName.ifEmpty { "Series" },
            cover = cover.orEmpty(),
        )
    }
}

@Serializable
internal data class XtreamSeriesInfo(
    @SerialName("episodes") val episodes: Map<String, List<XtreamEpisode>>? = null,
)

@Serializable
internal data class XtreamEpisode(
    @SerialName("id") val id: String = "",
    @SerialName("episode_num") val episodeNum: String? = null,
    @SerialName("title") val title: String? = null,
    @SerialName("container_extension") val containerExtension: String? = null,
    @SerialName("info") val info: XtreamEpisodeInfo? = null,
)

@Serializable
internal data class XtreamEpisodeInfo(
    @SerialName("movie_image") val movieImage: String? = null,
)

/** Some endpoints return `category_id` as an int, others as a string. */
private fun JsonElement?.asScalarString(): String = when (this) {
    null -> ""
    is JsonPrimitive -> if (isString) content else content
    else -> ""
}
