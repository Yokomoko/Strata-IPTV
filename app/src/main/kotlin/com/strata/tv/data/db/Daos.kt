package com.strata.tv.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Upsert
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow
import java.time.Instant

// ---------------------------------------------------------------------------
// Sources
// ---------------------------------------------------------------------------
@Dao
interface SourceDao {
    @Query("SELECT * FROM sources")
    suspend fun all(): List<SourceEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(source: SourceEntity): Long

    @Query("UPDATE sources SET last_synced = :at WHERE id = :id")
    suspend fun markSynced(id: Int, at: Instant)
}

// ---------------------------------------------------------------------------
// Content items — base table.
// ---------------------------------------------------------------------------
@Dao
interface ContentDao {
    @Upsert
    suspend fun upsertAll(items: List<ContentItemEntity>)

    @Query("SELECT * FROM content_items WHERE content_id = :contentId LIMIT 1")
    suspend fun byContentId(contentId: String): ContentItemEntity?

    @Query("SELECT * FROM content_items WHERE content_type = :type")
    suspend fun byType(type: String): List<ContentItemEntity>

    /**
     * Word-split search: matches rows where **any** significant word
     * in the query appears in display_name, title, or tvg_name.
     *
     * This is intentionally broad — it over-fetches so that the
     * Kotlin-side [FuzzyMatch] scorer can rank and prune the results,
     * catching typos and partial matches that a strict SQL LIKE would
     * miss.  The LIMIT 500 keeps the candidate set bounded.
     */
    @RawQuery
    suspend fun searchRaw(query: SupportSQLiteQuery): List<ContentItemEntity>

    companion object {
        private val STOP_WORDS = setOf(
            "the", "a", "an", "of", "in", "on", "at", "to", "for",
            "is", "it", "and", "or", "by", "as", "be", "no", "so",
        )

        /** Expand ordinals and common shorthand so "1st" finds "first" etc. */
        private val EXPANSIONS = mapOf(
            "1st" to "first", "2nd" to "second", "3rd" to "third",
            "4th" to "fourth", "5th" to "fifth", "6th" to "sixth",
            "7th" to "seventh", "8th" to "eighth", "9th" to "ninth",
            "10th" to "tenth",
            // Reverse too
            "first" to "1st", "second" to "2nd", "third" to "3rd",
        )

        fun buildSearchQuery(query: String): SupportSQLiteQuery {
            val rawWords = query.trim().lowercase()
                .split(Regex("\\s+"))
                .filter { it.length >= 3 && it !in STOP_WORDS }
                .distinct()

            // Fall back to the raw query if no words survived filtering.
            val terms = rawWords.ifEmpty { listOf(query.trim().lowercase()) }

            val clauses = mutableListOf<String>()
            val args = mutableListOf<String>()

            for (word in terms) {
                val prefix = if (word.length > 4) word.take(4) else word
                val pattern = "%$prefix%"

                // Check if the word has an expansion (e.g., "1st" ↔ "first")
                val expanded = EXPANSIONS[word]
                if (expanded != null) {
                    val expPrefix = if (expanded.length > 4) expanded.take(4) else expanded
                    val expPattern = "%$expPrefix%"
                    // OR: match either the original or the expansion
                    clauses.add(
                        "((LOWER(display_name) LIKE ? OR LOWER(title) LIKE ? OR LOWER(tvg_name) LIKE ?) OR " +
                        "(LOWER(display_name) LIKE ? OR LOWER(title) LIKE ? OR LOWER(tvg_name) LIKE ?))",
                    )
                    args.addAll(listOf(pattern, pattern, pattern, expPattern, expPattern, expPattern))
                } else {
                    clauses.add(
                        "(LOWER(display_name) LIKE ? OR LOWER(title) LIKE ? OR LOWER(tvg_name) LIKE ?)",
                    )
                    args.addAll(listOf(pattern, pattern, pattern))
                }
            }

            val sql = "SELECT * FROM content_items WHERE ${clauses.joinToString(" AND ")} LIMIT 200"
            return SimpleSQLiteQuery(sql, args.toTypedArray())
        }
    }
}

// ---------------------------------------------------------------------------
// Channels — joined with content_items in the live screen via the repo.
// ---------------------------------------------------------------------------
@Dao
interface ChannelDao {
    @Upsert
    suspend fun upsertAll(channels: List<ChannelEntity>)

    @Query("SELECT * FROM channels")
    fun watchAll(): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channels WHERE is_favourite = 1")
    fun watchFavourites(): Flow<List<ChannelEntity>>

    @Query("UPDATE channels SET is_favourite = :fav WHERE content_id = :contentId")
    suspend fun setFavourite(contentId: String, fav: Boolean)

    @Query("UPDATE channels SET last_watched = :at WHERE content_id = :contentId")
    suspend fun markWatched(contentId: String, at: Instant)
}

// ---------------------------------------------------------------------------
// Movies — heavy DAO since the home + Movies screens both read here.
// ---------------------------------------------------------------------------
@Dao
interface MovieDao {
    @Upsert
    suspend fun upsertAll(movies: List<MovieEntity>)

    @Query("SELECT * FROM movies WHERE hidden = 0 ORDER BY year DESC")
    fun watchAllByYear(): Flow<List<MovieEntity>>

    /** Single movie by content_id — for detail screens. */
    @Query("SELECT * FROM movies WHERE content_id = :contentId LIMIT 1")
    suspend fun byContentId(contentId: String): MovieEntity?

    /**
     * Lightweight projection for grid/rail list views.  Omits heavy text
     * columns (overview, cast) that cause CursorWindow overflow when the
     * library has 2000+ movies.
     */
    /**
     * Lightweight projection for grid/rail list views.  Omits heavy text
     * columns (overview, cast) that cause CursorWindow overflow.
     * Capped at 500 rows — more than enough for genre rails + hero;
     * even 15-column rows overflow the 2 MB CursorWindow at ~3000 rows
     * due to long poster URLs and titles.
     */
    @Query(
        """
        SELECT id, content_id, movie_title, year, runtime, genre,
               poster_url, resume_position_ms, watched, is_favourite,
               language, rating, provider, tmdb_id, hidden
        FROM movies WHERE hidden = 0
          AND (year IS NULL OR year BETWEEN 1900 AND 2030)
        ORDER BY year DESC
        LIMIT 500
        """,
    )
    fun watchAllForList(): Flow<List<MovieListItem>>

    /** Recent movies for the Home screen rails (shows initials fallback when no poster). */
    @Query(
        """
        SELECT id, content_id, movie_title, year, runtime, genre,
               poster_url, resume_position_ms, watched, is_favourite,
               language, rating, provider, tmdb_id, hidden
        FROM movies WHERE hidden = 0
          AND (year IS NULL OR year BETWEEN 1900 AND 2030)
        ORDER BY year DESC
        LIMIT :limit
        """,
    )
    fun watchRecentWithPosters(limit: Int = 20): Flow<List<MovieListItem>>

    /** Recent movies with backdrops for the Home hero carousel (full entity for overview/cast). */
    @Query(
        """
        SELECT * FROM movies
        WHERE hidden = 0 AND backdrop_url != '' AND poster_url != ''
          AND (year IS NULL OR year BETWEEN 2025 AND 2030)
        ORDER BY rating DESC
        LIMIT :limit
        """,
    )
    fun watchHeroCandidates(limit: Int = 5): Flow<List<MovieEntity>>

    /** Movies with posters for a specific genre — for Home genre rails. */
    @Query(
        """
        SELECT id, content_id, movie_title, year, runtime, genre,
               poster_url, resume_position_ms, watched, is_favourite,
               language, rating, provider, tmdb_id, hidden
        FROM movies WHERE hidden = 0 AND poster_url != ''
          AND (year IS NULL OR year BETWEEN 1900 AND 2030)
          AND genre LIKE '%' || :genre || '%'
        ORDER BY rating DESC
        LIMIT :limit
        """,
    )
    suspend fun byGenre(genre: String, limit: Int = 20): List<MovieListItem>

    /** Distinct genres across all visible movies. */
    @Query(
        """
        SELECT DISTINCT genre FROM movies
        WHERE hidden = 0 AND genre != '' AND poster_url != ''
        LIMIT 200
        """,
    )
    suspend fun distinctGenres(): List<String>

    /** Distinct group_titles for movies — used to build provider rails on Home. */
    @Query(
        """
        SELECT DISTINCT c.group_title
        FROM content_items c
        INNER JOIN movies m ON m.content_id = c.content_id
        WHERE m.hidden = 0 AND m.poster_url != '' AND c.group_title != ''
          AND (m.year IS NULL OR m.year BETWEEN 1900 AND 2030)
        """,
    )
    suspend fun distinctMovieGroupTitles(): List<String>

    /** Movies by TMDB provider — lightweight projection for Home rails. */
    @Query(
        """
        SELECT id, content_id, movie_title, year, runtime, genre,
               poster_url, resume_position_ms, watched, is_favourite,
               language, rating, provider, tmdb_id, hidden
        FROM movies WHERE hidden = 0 AND poster_url != '' AND provider = :provider
          AND (year IS NULL OR year BETWEEN 1900 AND 2030)
        ORDER BY rating DESC
        LIMIT :limit
        """,
    )
    suspend fun byProviderForList(provider: String, limit: Int = 20): List<MovieListItem>

    /** Movies by group_title (provider) — joined with content_items. */
    @Query(
        """
        SELECT m.id, m.content_id, m.movie_title, m.year, m.runtime, m.genre,
               m.poster_url, m.resume_position_ms, m.watched, m.is_favourite,
               m.language, m.rating, m.provider, m.tmdb_id, m.hidden
        FROM movies m
        INNER JOIN content_items c ON c.content_id = m.content_id
        WHERE m.hidden = 0 AND m.poster_url != '' AND c.group_title = :groupTitle
          AND (m.year IS NULL OR m.year BETWEEN 1900 AND 2030)
        ORDER BY m.rating DESC
        LIMIT :limit
        """,
    )
    suspend fun byGroupTitle(groupTitle: String, limit: Int = 20): List<MovieListItem>

    @Query(
        """
        SELECT * FROM movies
        WHERE hidden = 0 AND provider = :provider
        ORDER BY year DESC
        LIMIT :limit
        """,
    )
    fun watchByProvider(provider: String, limit: Int = 40): Flow<List<MovieEntity>>

    @Query("SELECT COUNT(*) FROM movies WHERE hidden = 0 AND (poster_url = '' OR genre = '' OR rating = 0.0 OR provider = '')")
    suspend fun countNeedingEnrichment(): Int

    /** Movies eligible for TMDB enrichment (missing poster, genre, rating, or provider). */
    @Query(
        """
        SELECT * FROM movies
        WHERE hidden = 0
          AND (poster_url = '' OR genre = '' OR rating = 0.0 OR provider = '')
        ORDER BY poster_url ASC, year DESC
        LIMIT :limit
        """,
    )
    suspend fun needingEnrichment(limit: Int = 500): List<MovieEntity>

    /** Movies that have a TMDB ID but no `provider` value yet. */
    @Query(
        """
        SELECT * FROM movies
        WHERE tmdb_id > 0 AND provider = '' AND hidden = 0
        LIMIT :limit
        """,
    )
    suspend fun needingProviderLookup(limit: Int = 200): List<MovieEntity>

    @Query(
        """
        UPDATE movies SET poster_url = :poster, genre = :genre, rating = :rating,
            language = :language, hidden = :hidden, tmdb_id = :tmdbId
        WHERE content_id = :contentId
        """,
    )
    suspend fun updateMetadata(
        contentId: String,
        poster: String,
        genre: String,
        rating: Double,
        language: String,
        hidden: Boolean,
        tmdbId: Int,
    )

    /** Persist the richer detail fields from a TMDB /movie/{id} call. */
    @Query(
        """
        UPDATE movies SET overview = :overview, backdrop_url = :backdropUrl,
            cast = :cast, certification = :certification,
            runtime = CASE WHEN :runtime IS NOT NULL THEN :runtime ELSE runtime END,
            genre = CASE WHEN :genre != '' THEN :genre ELSE genre END,
            year = CASE WHEN :year IS NOT NULL THEN :year ELSE year END
        WHERE content_id = :contentId
        """,
    )
    suspend fun updateDetail(
        contentId: String,
        overview: String,
        backdropUrl: String,
        cast: String,
        certification: String,
        runtime: Int?,
        genre: String,
        year: Int?,
    )

    /** Movies that have a TMDB ID but are missing detail enrichment. */
    @Query(
        """
        SELECT * FROM movies
        WHERE tmdb_id > 0 AND hidden = 0
          AND (overview = '' OR backdrop_url = '')
        LIMIT :limit
        """,
    )
    suspend fun needingDetailEnrichment(limit: Int = 200): List<MovieEntity>

    @Query("UPDATE movies SET provider = :provider WHERE content_id = :contentId")
    suspend fun updateProvider(contentId: String, provider: String)

    @Query("UPDATE movies SET trailer_url = :url WHERE content_id = :contentId")
    suspend fun updateTrailerUrl(contentId: String, url: String)

    @Query("UPDATE movies SET resume_position_ms = :pos, watched = :watched WHERE content_id = :contentId")
    suspend fun updateProgress(contentId: String, pos: Long, watched: Boolean)

    /** GROUP-BY for the home screen's "which providers have content" check. */
    @Query("SELECT provider, COUNT(*) AS count FROM movies WHERE hidden = 0 AND provider != '' GROUP BY provider")
    suspend fun countsByProvider(): List<ProviderCount>

    @Query("SELECT COUNT(*) FROM movies WHERE hidden = 0")
    fun watchVisibleCount(): Flow<Int>

    /** All movies including hidden variants — used by [MovieDeduplicator]. */
    @Query("SELECT * FROM movies")
    suspend fun allIncludingHidden(): List<MovieEntity>

    /** Set the hidden flag on a single movie by row ID. */
    @Query("UPDATE movies SET hidden = :hidden WHERE id = :id")
    suspend fun setHidden(id: Int, hidden: Boolean)
}

// ---------------------------------------------------------------------------
// Series + Episodes
// ---------------------------------------------------------------------------
@Dao
interface SeriesDao {
    @Upsert
    suspend fun upsertAll(series: List<SeriesEntity>)

    @Query("SELECT * FROM series WHERE hidden = 0")
    fun watchAll(): Flow<List<SeriesEntity>>

    @Query("SELECT * FROM series WHERE series_title = :title COLLATE NOCASE LIMIT 1")
    suspend fun byTitle(title: String): SeriesEntity?

    @Query(
        """
        SELECT * FROM series
        WHERE hidden = 0 AND provider = :provider
        ORDER BY id DESC
        LIMIT :limit
        """,
    )
    fun watchByProvider(provider: String, limit: Int = 40): Flow<List<SeriesEntity>>

    @Query("SELECT * FROM series WHERE poster_url = '' AND hidden = 0 LIMIT :limit")
    suspend fun needingEnrichment(limit: Int = 200): List<SeriesEntity>

    /** Series with a TMDB ID but missing detail enrichment fields. */
    @Query(
        """
        SELECT * FROM series
        WHERE tmdb_id > 0 AND hidden = 0
          AND (plot = '' OR backdrop_url = '')
        LIMIT :limit
        """,
    )
    suspend fun needingDetailEnrichment(limit: Int = 200): List<SeriesEntity>

    @Query(
        """
        UPDATE series SET poster_url = :poster, backdrop_url = :backdrop,
            plot = :plot, genre = :genre, language = :language,
            hidden = :hidden, tmdb_id = :tmdbId,
            total_seasons = :totalSeasons, total_episodes = :totalEpisodes
        WHERE series_title = :title
        """,
    )
    suspend fun updateMetadata(
        title: String,
        poster: String,
        backdrop: String,
        plot: String,
        genre: String,
        language: String,
        hidden: Boolean,
        tmdbId: Int,
        totalSeasons: Int,
        totalEpisodes: Int,
    )

    /** Persist the richer detail fields from a TMDB /tv/{id} call. */
    @Query(
        """
        UPDATE series SET plot = :plot, backdrop_url = :backdropUrl,
            cast = :cast, certification = :certification,
            genre = CASE WHEN :genre != '' THEN :genre ELSE genre END,
            first_air_year = CASE WHEN :firstAirYear IS NOT NULL THEN :firstAirYear ELSE first_air_year END
        WHERE series_title = :title
        """,
    )
    suspend fun updateDetail(
        title: String,
        plot: String,
        backdropUrl: String,
        cast: String,
        certification: String,
        genre: String,
        firstAirYear: Int?,
    )

    /** All series including hidden variants — used by [MovieDeduplicator.dedupSeries]. */
    @Query("SELECT * FROM series")
    suspend fun allIncludingHidden(): List<SeriesEntity>

    /** Set the hidden flag on a single series by title. */
    @Query("UPDATE series SET hidden = :hidden WHERE series_title = :title")
    suspend fun setHidden(title: String, hidden: Boolean)

    @Query("UPDATE series SET hidden = 1 WHERE series_title = :title")
    suspend fun hide(title: String)

    @Query("UPDATE series SET provider = :provider WHERE series_title = :title")
    suspend fun updateProvider(title: String, provider: String)

    @Query("SELECT provider, COUNT(*) AS count FROM series WHERE hidden = 0 AND provider != '' GROUP BY provider")
    suspend fun countsByProvider(): List<ProviderCount>

    @Query("SELECT COUNT(*) FROM series WHERE hidden = 0")
    fun watchCount(): Flow<Int>

    /** Series with a TMDB ID that still have episodes with empty names. */
    @Query(
        """
        SELECT DISTINCT s.* FROM series s
        INNER JOIN episodes e ON e.series_title = s.series_title
        WHERE s.tmdb_id > 0 AND s.hidden = 0
          AND e.episode_title = ''
        LIMIT :limit
        """,
    )
    suspend fun needingEpisodeNameEnrichment(limit: Int = 50): List<SeriesEntity>
}

@Dao
interface EpisodeDao {
    @Upsert
    suspend fun upsertAll(episodes: List<EpisodeEntity>)

    @Query("SELECT * FROM episodes WHERE series_title = :title COLLATE NOCASE ORDER BY season_number, episode_number")
    fun watchSeries(title: String): Flow<List<EpisodeEntity>>

    /** First episode of a series (lowest season, then lowest episode) — for "play series" shortcuts. */
    @Query(
        """
        SELECT * FROM episodes WHERE series_title = :title COLLATE NOCASE
        ORDER BY season_number, episode_number LIMIT 1
        """,
    )
    suspend fun firstOf(title: String): EpisodeEntity?

    @Query("UPDATE episodes SET resume_position_ms = :pos, watched = :watched WHERE content_id = :contentId")
    suspend fun updateProgress(contentId: String, pos: Long, watched: Boolean)

    @Query(
        """
        UPDATE episodes SET episode_title = :title
        WHERE series_title = :series
          AND season_number = :season
          AND episode_number = :episode
        """,
    )
    suspend fun updateName(series: String, season: Int, episode: Int, title: String)

    /**
     * Look up the next episode after (season, episode) within the same series.
     * Tries the next episode in the same season first, then the first episode
     * of the next season.
     */
    @Query(
        """
        SELECT * FROM episodes
        WHERE (series_title = :seriesTitle COLLATE NOCASE)
          AND (
            (season_number = :season AND episode_number > :episode)
            OR season_number > :season
          )
        ORDER BY season_number ASC, episode_number ASC
        LIMIT 1
        """,
    )
    suspend fun nextEpisode(seriesTitle: String, season: Int, episode: Int): EpisodeEntity?
}

// ---------------------------------------------------------------------------
// Programmes — EPG entries, very large table on a typical playlist.
// ---------------------------------------------------------------------------
@Dao
interface ProgrammeDao {
    @Upsert
    suspend fun upsertAll(programmes: List<ProgrammeEntity>)

    @Query(
        """
        SELECT * FROM programmes
        WHERE start_time < :to AND end_time > :from
        ORDER BY start_time ASC
        """,
    )
    suspend fun inRange(from: Instant, to: Instant): List<ProgrammeEntity>

    @Query("DELETE FROM programmes WHERE end_time < :before")
    suspend fun purgeBefore(before: Instant)

    @Query("SELECT DISTINCT channel_id FROM programmes WHERE channel_id != ''")
    suspend fun distinctChannelIds(): List<String>

    /** Cheap check used by the EPG fetch worker to decide if data is still fresh. */
    @Query("SELECT EXISTS(SELECT 1 FROM programmes WHERE end_time > :cutoff)")
    suspend fun hasAfter(cutoff: Instant): Boolean
}

// ---------------------------------------------------------------------------
// Continue Watching
// ---------------------------------------------------------------------------
@Dao
interface ContinueWatchingDao {
    /** Notification-emitting upsert — the rail re-renders. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: ContinueWatchingEntity)

    /**
     * Silent upsert used by the player's periodic save — Room emits
     * the change through Flow watchers regardless of the call site, so
     * we mitigate by collapsing position updates client-side: the
     * caller throttles to once per N seconds.  See Phase 4 player.
     */
    @Query(
        """
        INSERT OR REPLACE INTO continue_watching
            (content_id, content_type, stream_url, artwork_url,
             resume_position_ms, total_duration_ms, last_updated)
        VALUES (:contentId, :contentType, :streamUrl, :artworkUrl,
                :positionMs, :totalMs, :lastUpdated)
        """,
    )
    suspend fun upsertSilent(
        contentId: String,
        contentType: String,
        streamUrl: String,
        artworkUrl: String,
        positionMs: Long,
        totalMs: Long,
        lastUpdated: Instant,
    )

    @Query("SELECT * FROM continue_watching ORDER BY last_updated DESC LIMIT :limit")
    fun watchAll(limit: Int = 20): Flow<List<ContinueWatchingEntity>>

    @Query("DELETE FROM continue_watching WHERE content_id = :contentId")
    suspend fun delete(contentId: String)
}

// ---------------------------------------------------------------------------
// Watch history
// ---------------------------------------------------------------------------
@Dao
interface WatchHistoryDao {
    @Insert
    suspend fun insert(entry: WatchHistoryEntity)

    @Query("SELECT * FROM watch_history ORDER BY watched_at DESC LIMIT :limit")
    fun watchRecent(limit: Int = 50): Flow<List<WatchHistoryEntity>>
}

// ---------------------------------------------------------------------------
// Favourites
// ---------------------------------------------------------------------------
@Dao
interface FavouriteDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun add(entry: FavouriteEntity)

    @Query("DELETE FROM favourites WHERE content_id = :contentId")
    suspend fun remove(contentId: String)

    @Query("SELECT * FROM favourites")
    fun watchAll(): Flow<List<FavouriteEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM favourites WHERE content_id = :contentId)")
    fun watchIsFavourite(contentId: String): Flow<Boolean>
}

// ---------------------------------------------------------------------------
// Watchlist (#25 from v1 backlog, landed at the same time)
// ---------------------------------------------------------------------------
@Dao
interface WatchlistDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun add(entry: WatchlistEntity)

    @Query("DELETE FROM watchlist WHERE content_id = :contentId")
    suspend fun remove(contentId: String)

    @Query("SELECT * FROM watchlist ORDER BY added_at DESC")
    fun watchAll(): Flow<List<WatchlistEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM watchlist WHERE content_id = :contentId)")
    fun watchIsInWatchlist(contentId: String): Flow<Boolean>
}

// ---------------------------------------------------------------------------
// Helper projection used by the home screen.
// ---------------------------------------------------------------------------
data class ProviderCount(
    @androidx.room.ColumnInfo(name = "provider") val provider: String,
    @androidx.room.ColumnInfo(name = "count") val count: Int,
)
