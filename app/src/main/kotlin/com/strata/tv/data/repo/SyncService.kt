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
import com.strata.tv.data.settings.SettingsRepository
import com.strata.tv.domain.ChannelCategorizer
import com.strata.tv.domain.ChannelDeduplicator
import com.strata.tv.domain.ContentIdHasher
import com.strata.tv.domain.ContentType
import com.strata.tv.domain.MovieDeduplicator
import com.strata.tv.domain.SkyChannelNumbers
import com.strata.tv.domain.TitleParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
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
    private val progressTracker: com.strata.tv.data.tmdb.EnrichmentProgressTracker,
    private val settingsRepo: SettingsRepository,
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

            // Snapshot filter settings once so the rules stay consistent
            // for the whole sync run.
            val settings = settingsRepo.current()
            val excludedCategories = settings.excludedCategories
                .map { it.lowercase() }
                .toSet()
            val countryWhitelist = settings.countryWhitelist
                .map { it.uppercase() }
                .toSet()

            fun isExcluded(entry: M3uEntry): Boolean {
                val group = entry.groupTitle
                // Country filter: extract prefix before the first "|" and
                // require it to be in the whitelist.  Empty whitelist
                // means "accept everything".
                if (countryWhitelist.isNotEmpty() && group.contains('|')) {
                    val prefix = group.substringBefore('|').trim().uppercase()
                    if (prefix.isNotEmpty() && prefix !in countryWhitelist) {
                        return true
                    }
                }
                // Category filter: drop anything whose group-title matches
                // a blacklisted keyword (substring match, case-insensitive).
                if (excludedCategories.isNotEmpty()) {
                    val g = group.lowercase()
                    if (excludedCategories.any { needle -> g.contains(needle) }) {
                        return true
                    }
                }
                return false
            }

            // Buffer entries by content type so we can group + dedupe at
            // the end.  Dedup needs the full live list before it can
            // pick the best variant per logical channel.
            val live = mutableListOf<M3uEntry>()
            val movies = mutableListOf<M3uEntry>()
            val episodes = mutableListOf<M3uEntry>()

            // Stream the playlist body line-by-line (perf review #10).
            // Previously `response.body.string()` materialised the full
            // 20-50 MB playlist as a single UTF-8 String on the heap
            // before the parser even started — combined with the three
            // mutableListOf buffers below, first-launch sync could
            // transiently consume 60-100 MB of heap and trigger the
            // Fire Stick low-memory killer.
            //
            // The whole HTTP → parse pipeline runs inside the `use { }`
            // block so the response is closed the moment we exit, even
            // on cancellation or mid-parse throw.
            withContext(Dispatchers.IO) {
                http.newCall(Request.Builder().url(playlistUrl).build())
                    .execute()
                    .use { response ->
                        if (!response.isSuccessful) {
                            error("HTTP ${response.code} fetching $playlistUrl")
                        }
                        val body = response.body ?: error("Empty playlist body")
                        body.charStream().buffered().useLines { lines ->
                            parser.parseLines(lines).collect { result ->
                                when (result) {
                                    is ParseResult.Batch -> {
                                        for (entry in result.entries) {
                                            if (isExcluded(entry)) continue
                                            when (entry.contentType) {
                                                ContentType.Live -> live.add(entry)
                                                ContentType.Movie -> movies.add(entry)
                                                ContentType.Show -> episodes.add(entry)
                                            }
                                        }
                                        progressTracker.syncAdvance(result.entries.size)
                                    }
                                    is ParseResult.Progress -> {
                                        _progress.value = Progress.Parsing(result.parsed, result.skipped)
                                    }
                                    is ParseResult.Complete -> {
                                        progressTracker.syncComplete(result.totalParsed)
                                    }
                                }
                            }
                        }
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
            // Build a useful message: outer class + outer message +
            // root cause class + root cause message.  Avoids the
            // dreaded "null" surfaces from exceptions whose message
            // field happens to be empty.
            val rootCause = generateSequence(e) { it.cause }.last()
            val msg = buildString {
                append(e::class.simpleName ?: "Throwable")
                e.message?.let { append(": ").append(it) }
                if (rootCause !== e) {
                    append(" (cause: ")
                    append(rootCause::class.simpleName ?: "Throwable")
                    rootCause.message?.let { append(": ").append(it) }
                    append(")")
                }
            }
            _progress.value = Progress.Error(msg)
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

            // Deduplicate quality variants: group by (season, episode),
            // keep only the best quality variant per episode slot.
            val dedupedEpisodes = episodesForSeries
                .groupBy { (it.seasonNumber ?: 0) to (it.episodeNumber ?: 0) }
                .values
                .map { variants ->
                    variants.maxByOrNull {
                        MovieDeduplicator.detectQuality(it.displayName, it.streamUrl).ordinal
                    } ?: variants.first()
                }

            // "NEW" badge bookkeeping: preserve the user's seen-state
            // across the Upsert.  Room's @Upsert overwrites every
            // column, so without this read-modify-write the badge would
            // either (a) reset to 0 on every sync and falsely flag the
            // whole library as new, or (b) get bumped past `total` and
            // suppress legitimate badges.  Rules:
            //   - Series didn't exist before -> seen = newTotal so a
            //     fresh import doesn't flood the home screen with NEW.
            //   - Episode count grew -> seen = previousTotal so the
            //     badge fires on the delta.
            //   - Otherwise -> seen = previousSeen (preserve as-is).
            val newTotalEpisodes = dedupedEpisodes.size
            val previousTotal = seriesDao.getTotalEpisodes(titleForSeries)
            val previousSeen = seriesDao.getLastSeenTotalEpisodes(titleForSeries)
            val nextLastSeen = when {
                previousTotal == null -> newTotalEpisodes
                newTotalEpisodes > previousTotal -> previousTotal
                else -> previousSeen ?: newTotalEpisodes
            }

            seriesRows.add(
                SeriesEntity(
                    seriesTitle = titleForSeries,
                    totalSeasons = dedupedEpisodes
                        .mapNotNull { it.seasonNumber }
                        .maxOrNull() ?: 0,
                    totalEpisodes = newTotalEpisodes,
                    lastSeenTotalEpisodes = nextLastSeen,
                ),
            )

            for (entry in dedupedEpisodes) {
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
                        seriesTitle = titleForSeries,
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

}
