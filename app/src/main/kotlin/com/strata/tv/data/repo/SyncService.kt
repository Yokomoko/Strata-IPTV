package com.strata.tv.data.repo

import com.strata.tv.data.db.ChannelDao
import com.strata.tv.data.db.ChannelEntity
import com.strata.tv.data.db.ContentDao
import com.strata.tv.data.db.ContentItemEntity
import com.strata.tv.data.db.EpisodeDao
import com.strata.tv.data.db.EpisodeEntity
import com.strata.tv.data.db.MovieDao
import com.strata.tv.data.db.MovieEntity
import com.strata.tv.data.db.SeriesDao
import com.strata.tv.data.db.SeriesEntity
import com.strata.tv.data.m3u.M3uEntry
import com.strata.tv.data.m3u.M3uParser
import com.strata.tv.data.m3u.ParseResult
import com.strata.tv.domain.ChannelCategorizer
import com.strata.tv.domain.ChannelDeduplicator
import com.strata.tv.domain.ContentIdHasher
import com.strata.tv.domain.ContentType
import com.strata.tv.domain.SkyChannelNumbers
import com.strata.tv.domain.TitleParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pulls the user's M3U playlist from the network, runs the parsed
 * entries through classification + deduplication, and writes the
 * results into Room.
 *
 * Layered like this so unit tests can drive the parser directly:
 * - [M3uParser] does the regex-heavy work and emits batches.
 * - [ChannelCategorizer] sub-categorises live channels.
 * - [ChannelDeduplicator] collapses HEVC/FHD/UKHD variants to one
 *   channel each, keeping the best quality.
 * - [SyncService.syncFromUrl] orchestrates: HTTP → parse → classify
 *   → dedup → upsert to Room.
 *
 * Progress is exposed as [progress] (a [StateFlow]) so the UI can
 * show a sync indicator without polling.
 */
@Singleton
class SyncService @Inject constructor(
    private val http: OkHttpClient,
    private val parser: M3uParser,
    private val contentDao: ContentDao,
    private val channelDao: ChannelDao,
    private val movieDao: MovieDao,
    private val seriesDao: SeriesDao,
    private val episodeDao: EpisodeDao,
) {

    sealed interface Progress {
        data object Idle : Progress
        data class Downloading(val bytesRead: Long) : Progress
        data class Parsing(val parsed: Int, val skipped: Int) : Progress
        data object PostProcessing : Progress
        data class Done(val totalParsed: Int, val totalSkipped: Int) : Progress
        data class Error(val message: String) : Progress
    }

    private val _progress = MutableStateFlow<Progress>(Progress.Idle)
    val progress: StateFlow<Progress> = _progress.asStateFlow()

    /**
     * Fetch [playlistUrl], parse, classify, dedup and write to Room.
     *
     * The whole pipeline runs on the calling coroutine — call this
     * from a `Dispatchers.IO` worker (e.g. [androidx.work.WorkManager])
     * so the main thread isn't blocked.  The function is suspending
     * and respects coroutine cancellation: cancelling it mid-parse is
     * safe because each Room upsert is its own transaction.
     */
    suspend fun syncFromUrl(playlistUrl: String, sourceId: Int) {
        try {
            _progress.value = Progress.Downloading(0)
            val body = withTimeoutNote("download") {
                http.newCall(Request.Builder().url(playlistUrl).build())
                    .execute()
                    .use { it.body?.string() ?: error("Empty playlist body") }
            }

            // Buffer entries by content type so we can group + dedupe at
            // the end.  Dedup needs the full live list before it can
            // pick the best variant per logical channel.
            val live = mutableListOf<M3uEntry>()
            val movies = mutableListOf<M3uEntry>()
            val episodes = mutableListOf<M3uEntry>()

            parser.parse(body).collect { result ->
                when (result) {
                    is ParseResult.Batch -> {
                        for (entry in result.entries) {
                            when (entry.contentType) {
                                ContentType.Live -> live.add(entry)
                                ContentType.Movie -> movies.add(entry)
                                ContentType.Show -> episodes.add(entry)
                            }
                        }
                    }
                    is ParseResult.Progress -> {
                        _progress.value = Progress.Parsing(result.parsed, result.skipped)
                    }
                    is ParseResult.Complete -> Unit
                }
            }

            _progress.value = Progress.PostProcessing

            persistLive(live, sourceId, playlistUrl)
            persistMovies(movies, sourceId, playlistUrl)
            persistShows(episodes, sourceId, playlistUrl)

            _progress.value = Progress.Done(
                totalParsed = live.size + movies.size + episodes.size,
                totalSkipped = 0,
            )
        } catch (e: Throwable) {
            _progress.value = Progress.Error(e.message ?: e::class.simpleName.orEmpty())
            throw e
        }
    }

    // -------------------------------------------------------------------------
    // Persisters
    // -------------------------------------------------------------------------

    private suspend fun persistLive(
        entries: List<M3uEntry>,
        sourceId: Int,
        sourceKey: String,
    ) {
        // Dedup in-memory first — keeps the per-row writes to one per
        // logical channel.  M3uEntry is the type the dedup operates on
        // since it has both displayName and tvgId.
        val deduped = ChannelDeduplicator.dedupe(
            channels = entries,
            displayName = M3uEntry::displayName,
            tvgId = M3uEntry::tvgId,
            withTvgId = { e, id -> e.copy(tvgId = id) },
        )

        val contentRows = mutableListOf<ContentItemEntity>()
        val channelRows = mutableListOf<ChannelEntity>()

        for (entry in deduped) {
            val contentId = ContentIdHasher.hash(
                sourceKey = sourceKey,
                normalisedTitle = TitleParser.normalise(
                    ChannelDeduplicator.cleanChannelName(entry.displayName),
                ),
                groupTitle = entry.groupTitle,
                streamUrl = entry.streamUrl,
            )

            contentRows.add(
                ContentItemEntity(
                    contentId = contentId,
                    sourceId = sourceId,
                    displayName = entry.displayName,
                    streamUrl = entry.streamUrl,
                    groupTitle = entry.groupTitle,
                    contentType = "live",
                    tvgId = entry.tvgId,
                    tvgName = entry.tvgName,
                    tvgLogo = entry.tvgLogo,
                    tvgType = entry.tvgType,
                ),
            )

            channelRows.add(
                ChannelEntity(
                    contentId = contentId,
                    category = ChannelCategorizer.categorise(entry.displayName, entry.groupTitle),
                    logoUrl = entry.tvgLogo,
                    channelNumber = SkyChannelNumbers.numberFor(
                        ChannelDeduplicator.cleanChannelName(entry.displayName),
                    ).takeIf { it != SkyChannelNumbers.UNKNOWN },
                ),
            )
        }

        contentDao.upsertAll(contentRows)
        channelDao.upsertAll(channelRows)
    }

    private suspend fun persistMovies(
        entries: List<M3uEntry>,
        sourceId: Int,
        sourceKey: String,
    ) {
        val contentRows = mutableListOf<ContentItemEntity>()
        val movieRows = mutableListOf<MovieEntity>()

        for (entry in entries) {
            val title = entry.movieTitle ?: TitleParser.stripHdPrefix(entry.displayName)
            val contentId = ContentIdHasher.hash(
                sourceKey = sourceKey,
                normalisedTitle = TitleParser.normalise(title),
                groupTitle = entry.groupTitle,
                streamUrl = entry.streamUrl,
            )

            contentRows.add(
                ContentItemEntity(
                    contentId = contentId,
                    sourceId = sourceId,
                    displayName = entry.displayName,
                    streamUrl = entry.streamUrl,
                    groupTitle = entry.groupTitle,
                    contentType = "movie",
                    tvgId = entry.tvgId,
                    tvgName = entry.tvgName,
                    tvgLogo = entry.tvgLogo,
                    tvgType = entry.tvgType,
                    title = title,
                ),
            )
            movieRows.add(
                MovieEntity(
                    contentId = contentId,
                    movieTitle = title,
                    year = entry.movieYear,
                ),
            )
        }

        contentDao.upsertAll(contentRows)
        movieDao.upsertAll(movieRows)
    }

    private suspend fun persistShows(
        entries: List<M3uEntry>,
        sourceId: Int,
        sourceKey: String,
    ) {
        // For shows we have one entity per *episode* (in episodes
        // table) plus one summary row per *series* in series table.
        // Group by series title first.
        val grouped = entries
            .filter { it.seriesTitle != null }
            .groupBy { TitleParser.normalise(it.seriesTitle!!) }

        val contentRows = mutableListOf<ContentItemEntity>()
        val seriesRows = mutableListOf<SeriesEntity>()
        val episodeRows = mutableListOf<EpisodeEntity>()

        for ((normalisedTitle, episodesForSeries) in grouped) {
            val first = episodesForSeries.first()
            val titleForSeries = first.seriesTitle!!.trim()

            seriesRows.add(
                SeriesEntity(
                    seriesTitle = normalisedTitle,
                    totalSeasons = episodesForSeries
                        .mapNotNull { it.seasonNumber }
                        .maxOrNull() ?: 0,
                    totalEpisodes = episodesForSeries.size,
                ),
            )

            for (entry in episodesForSeries) {
                val episodeContentId = ContentIdHasher.hash(
                    sourceKey = sourceKey,
                    normalisedTitle = "$normalisedTitle s${entry.seasonNumber}e${entry.episodeNumber}",
                    groupTitle = entry.groupTitle,
                    streamUrl = entry.streamUrl,
                )

                contentRows.add(
                    ContentItemEntity(
                        contentId = episodeContentId,
                        sourceId = sourceId,
                        displayName = entry.displayName,
                        streamUrl = entry.streamUrl,
                        groupTitle = entry.groupTitle,
                        contentType = "show",
                        tvgId = entry.tvgId,
                        tvgName = entry.tvgName,
                        tvgLogo = entry.tvgLogo,
                        tvgType = entry.tvgType,
                        title = titleForSeries,
                    ),
                )

                episodeRows.add(
                    EpisodeEntity(
                        contentId = episodeContentId,
                        seriesTitle = normalisedTitle,
                        seasonNumber = entry.seasonNumber ?: 0,
                        episodeNumber = entry.episodeNumber ?: 0,
                        streamUrl = entry.streamUrl,
                    ),
                )
            }
        }

        contentDao.upsertAll(contentRows)
        seriesDao.upsertAll(seriesRows)
        episodeDao.upsertAll(episodeRows)
    }

    /**
     * Wraps a network call so any failure surfaces with the stage it
     * was at — easier to triage in the device log than a bare IO
     * exception with no context.
     */
    private suspend inline fun <T> withTimeoutNote(stage: String, block: () -> T): T = try {
        block()
    } catch (e: Throwable) {
        throw RuntimeException("Sync failed during $stage: ${e.message}", e)
    }
}
