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
import com.strata.tv.data.xtream.XtreamJsonClient
import com.strata.tv.data.xtream.XtreamSnapshot
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
    private val xtreamJson: XtreamJsonClient,
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
     * Called by [com.strata.tv.ui.home.HomeViewModel] when it decides
     * no fresh sync is needed (returning user, sync frequency cap not
     * yet reached).  Without this, [progress] would stay [Progress.Idle]
     * and the gate that's now wired to wait for sync + enrichment would
     * never transition to Main.
     */
    fun markSkipped() {
        _progress.value = Progress.Done(totalParsed = 0, totalSkipped = 0)
    }

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

            // Try the M3U URL first, but peek the response body to
            // detect JSON-only Xtream providers (e.g. MyBunny.TV).
            // Their `get.php` returns a 354-byte JSON auth blob instead
            // of an M3U playlist, so the existing line-based parser
            // would parse zero entries and the user would never see
            // any content.  When we detect that, fall back to the
            // Xtream Codes JSON API (`player_api.php`) which is the
            // universal Xtream interface.
            val provider = settings.provider
            val useJsonApi = withContext(Dispatchers.IO) {
                http.newCall(Request.Builder().url(playlistUrl).build())
                    .execute()
                    .use { response ->
                        if (!response.isSuccessful) {
                            error("HTTP ${response.code} fetching $playlistUrl")
                        }
                        val body = response.body ?: error("Empty playlist body")
                        val contentType = response.header("Content-Type").orEmpty()

                        // Cheap peek: read up to ~512 bytes to detect
                        // whether the body looks like M3U (`#EXTM3U`)
                        // or JSON.  Avoids buffering the full 5-50 MB.
                        val source = body.source()
                        source.request(512)
                        val peek = source.peek().readUtf8(
                            minOf(512L, source.buffer.size),
                        ).trimStart()
                        val isJsonResponse = contentType.contains("json", ignoreCase = true) ||
                            peek.startsWith("{") || peek.startsWith("[")

                        if (isJsonResponse) {
                            // Bail out of the M3U path; JSON branch
                            // will issue fresh requests via
                            // XtreamJsonClient.
                            return@use true
                        }

                        // Standard M3U: stream the body line-by-line
                        // into the parser without loading the whole
                        // playlist as a String (Fire Stick has ~512 MB
                        // app heap shared with ExoPlayer + Compose).
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
                        false
                    }
            }

            var seriesMetaToPersist: List<com.strata.tv.data.xtream.XtreamSeriesMeta> = emptyList()
            if (useJsonApi) {
                val host = provider.host
                val user = provider.username
                val pass = provider.password
                if (host.isBlank() || user.isBlank() || pass.isBlank()) {
                    error(
                        "Provider returned JSON from get.php but no Xtream " +
                            "credentials are configured; cannot fall back",
                    )
                }
                val snapshot: XtreamSnapshot = xtreamJson.fetchAll(
                    host = host,
                    user = user,
                    pass = pass,
                    onProgress = { status ->
                        _progress.value = Progress.Parsing(
                            parsed = live.size + movies.size + episodes.size,
                            skipped = 0,
                        )
                        progressTracker.setLabel(status)
                    },
                )
                for (entry in snapshot.live) if (!isExcluded(entry)) live.add(entry)
                for (entry in snapshot.movies) if (!isExcluded(entry)) movies.add(entry)
                // Episodes are lazy-loaded per series at detail-screen
                // open time — we just persist the catalogue here.
                seriesMetaToPersist = snapshot.seriesMeta.filter { meta ->
                    if (countryWhitelist.isNotEmpty() && meta.groupTitle.contains('|')) {
                        val prefix = meta.groupTitle.substringBefore('|').trim().uppercase()
                        if (prefix.isNotEmpty() && prefix !in countryWhitelist) return@filter false
                    }
                    if (excludedCategories.isNotEmpty()) {
                        val g = meta.groupTitle.lowercase()
                        if (excludedCategories.any { needle -> g.contains(needle) }) return@filter false
                    }
                    true
                }
                progressTracker.syncComplete(
                    live.size + movies.size + episodes.size + seriesMetaToPersist.size,
                )
            }

            _progress.value = Progress.PostProcessing

            persistLive(live, sourceId, playlistUrl)
            persistMovies(movies, sourceId, playlistUrl)
            persistShows(episodes, sourceId, playlistUrl)
            persistSeriesCatalogue(seriesMetaToPersist)

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

    /**
     * Persist a Xtream series catalogue (metadata only — episodes will
     * be lazy-loaded by the show detail screen).  We don't blow away
     * the user's existing per-series progress columns when we re-sync;
     * `upsertAll` preserves them via Room's `@Upsert` matching on the
     * unique `series_title` index, except for `xtream_series_id` which
     * we always overwrite so the lazy fetcher has a current id.
     */
    private suspend fun persistSeriesCatalogue(
        snapshot: List<com.strata.tv.data.xtream.XtreamSeriesMeta>,
    ) {
        if (snapshot.isEmpty()) return
        val rows = snapshot.map { meta ->
            // Preserve totals/last-seen counters across re-sync — if a
            // row already exists, we only need to update xtream_series_id.
            // If it doesn't, we insert a fresh row with sane defaults.
            // SeriesEnrichmentService will fill in poster/backdrop/plot
            // separately when it next runs.
            val existing = seriesDao.byTitle(meta.title)
            existing?.copy(xtreamSeriesId = meta.xtreamSeriesId)
                ?: SeriesEntity(
                    seriesTitle = meta.title,
                    xtreamSeriesId = meta.xtreamSeriesId,
                )
        }
        seriesDao.upsertAll(rows)
    }

}
