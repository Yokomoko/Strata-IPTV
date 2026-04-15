package com.strata.tv.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant

/**
 * Room entities mirroring the drift schema in v1's
 * `lib/core/database/tables/`.  Column names stay snake_case to
 * match v1 — that means we could (in theory) re-open the v1 SQLite
 * file from v2, though we don't actually do that.
 *
 * Indices are added inline where v1 had them via the schema-v8
 * migration.
 */

// ---------------------------------------------------------------------------
// Sources — one row per IPTV playlist URL the user has configured.
// ---------------------------------------------------------------------------
@Entity(tableName = "sources")
data class SourceEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    @ColumnInfo(name = "playlist_url") val playlistUrl: String,
    @ColumnInfo(name = "epg_url") val epgUrl: String = "",
    @ColumnInfo(name = "user_agent") val userAgent: String = "",
    @ColumnInfo(name = "last_synced") val lastSynced: Instant? = null,
)

// ---------------------------------------------------------------------------
// Content items — base table, every channel / movie / show row points
// back to a content_item row via `content_id`.
// ---------------------------------------------------------------------------
@Entity(
    tableName = "content_items",
    indices = [
        Index(value = ["content_id"], unique = true),
        Index(value = ["content_type"]),
    ],
)
data class ContentItemEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    @ColumnInfo(name = "content_id") val contentId: String,
    @ColumnInfo(name = "source_id") val sourceId: Int,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "stream_url") val streamUrl: String,
    @ColumnInfo(name = "group_title") val groupTitle: String = "",
    @ColumnInfo(name = "content_type") val contentType: String, // "live" | "movie" | "show"
    @ColumnInfo(name = "tvg_id") val tvgId: String = "",
    @ColumnInfo(name = "tvg_name") val tvgName: String = "",
    @ColumnInfo(name = "tvg_logo") val tvgLogo: String = "",
    @ColumnInfo(name = "tvg_type") val tvgType: String = "",
    @ColumnInfo(name = "title") val title: String = "",
    @ColumnInfo(name = "plot") val plot: String = "",
    @ColumnInfo(name = "artwork_url") val artworkUrl: String = "",
    @ColumnInfo(name = "backdrop_url") val backdropUrl: String = "",
    @ColumnInfo(name = "last_updated") val lastUpdated: Instant = Instant.now(),
)

// ---------------------------------------------------------------------------
// Channels — live-TV-specific extension of content_items.
// ---------------------------------------------------------------------------
@Entity(
    tableName = "channels",
    indices = [
        Index(value = ["content_id"], unique = true),
        // v4: fav-mode zapping filters `WHERE is_favourite = 1` on every
        // D-pad Up/Down.  With thousands of channels and few favourites,
        // a full table scan is wasteful — partial-covering index is cheap.
        Index(value = ["is_favourite"]),
    ],
)
data class ChannelEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    @ColumnInfo(name = "content_id") val contentId: String,
    @ColumnInfo(name = "category") val category: String = "",
    @ColumnInfo(name = "logo_url") val logoUrl: String = "",
    @ColumnInfo(name = "channel_number") val channelNumber: Int? = null,
    @ColumnInfo(name = "is_favourite") val isFavourite: Boolean = false,
    @ColumnInfo(name = "last_watched") val lastWatched: Instant? = null,
)

// ---------------------------------------------------------------------------
// Movies — VOD movie extension.
// ---------------------------------------------------------------------------
@Entity(
    tableName = "movies",
    indices = [
        Index(value = ["content_id"], unique = true),
        // v1 schema-v8 migration: filter the home rails by provider+hidden.
        Index(value = ["provider", "hidden"]),
        // Year DESC scan for "newest first" listing.
        Index(value = ["year"]),
        // v4: covers the very hot `WHERE hidden = 0 ORDER BY year DESC`
        // pattern used across Home (recent movies, hero candidates, provider
        // rails) and Movies screen.  Composite index satisfies the filter
        // and sort in one pass instead of filtering-then-sorting.
        Index(value = ["hidden", "year"]),
    ],
)
data class MovieEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    @ColumnInfo(name = "content_id") val contentId: String,
    @ColumnInfo(name = "movie_title") val movieTitle: String,
    @ColumnInfo(name = "year") val year: Int? = null,
    @ColumnInfo(name = "runtime") val runtime: Int? = null, // minutes
    @ColumnInfo(name = "genre") val genre: String = "",
    @ColumnInfo(name = "poster_url") val posterUrl: String = "",
    @ColumnInfo(name = "overview") val overview: String = "",
    @ColumnInfo(name = "backdrop_url") val backdropUrl: String = "",
    @ColumnInfo(name = "cast") val cast: String = "",
    @ColumnInfo(name = "certification") val certification: String = "",
    @ColumnInfo(name = "resume_position_ms") val resumePositionMs: Long = 0,
    @ColumnInfo(name = "watched") val watched: Boolean = false,
    @ColumnInfo(name = "is_favourite") val isFavourite: Boolean = false,
    @ColumnInfo(name = "language") val language: String = "",
    @ColumnInfo(name = "rating") val rating: Double = 0.0,
    @ColumnInfo(name = "provider") val provider: String = "",
    @ColumnInfo(name = "tmdb_id") val tmdbId: Int = 0,
    @ColumnInfo(name = "hidden") val hidden: Boolean = false,
    @ColumnInfo(name = "trailer_url", defaultValue = "") val trailerUrl: String = "",
)

/**
 * Lightweight projection of [MovieEntity] for grid/rail list views.
 * Omits heavy text columns (`overview`, `backdrop_url`, `cast`,
 * `certification`) that cause CursorWindow overflow when the library
 * has 2000+ movies.  Room maps this by column name — no @Entity needed.
 */
data class MovieListItem(
    val id: Int,
    @ColumnInfo(name = "content_id") val contentId: String,
    @ColumnInfo(name = "movie_title") val movieTitle: String,
    @ColumnInfo(name = "year") val year: Int?,
    @ColumnInfo(name = "runtime") val runtime: Int?,
    @ColumnInfo(name = "genre") val genre: String,
    @ColumnInfo(name = "poster_url") val posterUrl: String,
    @ColumnInfo(name = "resume_position_ms") val resumePositionMs: Long,
    @ColumnInfo(name = "watched") val watched: Boolean,
    @ColumnInfo(name = "is_favourite") val isFavourite: Boolean,
    @ColumnInfo(name = "language") val language: String,
    @ColumnInfo(name = "rating") val rating: Double,
    @ColumnInfo(name = "provider") val provider: String,
    @ColumnInfo(name = "tmdb_id") val tmdbId: Int,
    @ColumnInfo(name = "hidden") val hidden: Boolean,
)

// ---------------------------------------------------------------------------
// Series — TV series header, joined to episodes by `series_title`.
// ---------------------------------------------------------------------------
@Entity(
    tableName = "series",
    indices = [
        Index(value = ["series_title"], unique = true),
        Index(value = ["provider", "hidden"]),
    ],
)
data class SeriesEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    @ColumnInfo(name = "series_title") val seriesTitle: String,
    @ColumnInfo(name = "poster_url") val posterUrl: String = "",
    @ColumnInfo(name = "backdrop_url") val backdropUrl: String = "",
    @ColumnInfo(name = "plot") val plot: String = "",
    @ColumnInfo(name = "genre") val genre: String = "",
    @ColumnInfo(name = "cast") val cast: String = "",
    @ColumnInfo(name = "certification") val certification: String = "",
    @ColumnInfo(name = "first_air_year") val firstAirYear: Int? = null,
    @ColumnInfo(name = "tmdb_id") val tmdbId: Int = 0,
    @ColumnInfo(name = "language") val language: String = "",
    @ColumnInfo(name = "total_seasons") val totalSeasons: Int = 0,
    @ColumnInfo(name = "total_episodes") val totalEpisodes: Int = 0,
    @ColumnInfo(name = "is_favourite") val isFavourite: Boolean = false,
    @ColumnInfo(name = "hidden") val hidden: Boolean = false,
    @ColumnInfo(name = "provider") val provider: String = "",
)

// ---------------------------------------------------------------------------
// Episodes — joined to series by `series_title`.
// ---------------------------------------------------------------------------
@Entity(
    tableName = "episodes",
    indices = [
        Index(value = ["content_id"], unique = true),
        Index(value = ["series_title", "season_number", "episode_number"]),
    ],
)
data class EpisodeEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    @ColumnInfo(name = "content_id") val contentId: String,
    @ColumnInfo(name = "series_title") val seriesTitle: String,
    @ColumnInfo(name = "season_number") val seasonNumber: Int,
    @ColumnInfo(name = "episode_number") val episodeNumber: Int,
    @ColumnInfo(name = "episode_title") val episodeTitle: String = "",
    @ColumnInfo(name = "stream_url") val streamUrl: String,
    @ColumnInfo(name = "runtime") val runtime: Int? = null,
    @ColumnInfo(name = "resume_position_ms") val resumePositionMs: Long = 0,
    @ColumnInfo(name = "watched") val watched: Boolean = false,
)

// ---------------------------------------------------------------------------
// Programmes — XMLTV EPG entries.
// ---------------------------------------------------------------------------
@Entity(
    tableName = "programmes",
    indices = [
        Index(value = ["channel_id"]),
        Index(value = ["start_time", "end_time"]),
    ],
)
data class ProgrammeEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    @ColumnInfo(name = "channel_id") val channelId: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "description") val description: String = "",
    @ColumnInfo(name = "start_time") val startTime: Instant,
    @ColumnInfo(name = "end_time") val endTime: Instant,
    @ColumnInfo(name = "icon") val icon: String = "",
)

// ---------------------------------------------------------------------------
// Continue Watching.
// ---------------------------------------------------------------------------
@Entity(
    tableName = "continue_watching",
    indices = [Index(value = ["content_id"], unique = true)],
)
data class ContinueWatchingEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    @ColumnInfo(name = "content_id") val contentId: String,
    @ColumnInfo(name = "content_type") val contentType: String, // "live" | "movie" | "show"
    @ColumnInfo(name = "stream_url") val streamUrl: String = "",
    @ColumnInfo(name = "artwork_url") val artworkUrl: String = "",
    @ColumnInfo(name = "resume_position_ms") val resumePositionMs: Long,
    @ColumnInfo(name = "total_duration_ms") val totalDurationMs: Long = 0,
    @ColumnInfo(name = "last_updated") val lastUpdated: Instant = Instant.now(),
)

// ---------------------------------------------------------------------------
// Watch history — append-only log of every play, used for analytics
// and the "recent" rail.
// ---------------------------------------------------------------------------
@Entity(
    tableName = "watch_history",
    indices = [Index(value = ["watched_at"])],
)
data class WatchHistoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    @ColumnInfo(name = "content_id") val contentId: String,
    @ColumnInfo(name = "content_type") val contentType: String,
    @ColumnInfo(name = "duration_watched_ms") val durationWatchedMs: Long,
    @ColumnInfo(name = "watched_at") val watchedAt: Instant = Instant.now(),
)

// ---------------------------------------------------------------------------
// Favourites + Watchlist — separate tables for clarity.  v1 had
// favourites only; v2 lands #25 (Watchlist) at the same time.
// ---------------------------------------------------------------------------
@Entity(
    tableName = "favourites",
    indices = [Index(value = ["content_id"], unique = true)],
)
data class FavouriteEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    @ColumnInfo(name = "content_id") val contentId: String,
    @ColumnInfo(name = "content_type") val contentType: String,
    @ColumnInfo(name = "added_at") val addedAt: Instant = Instant.now(),
)

@Entity(
    tableName = "watchlist",
    indices = [
        Index(value = ["content_id"], unique = true),
        Index(value = ["added_at"]),
    ],
)
data class WatchlistEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    @ColumnInfo(name = "content_id") val contentId: String,
    @ColumnInfo(name = "content_type") val contentType: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "artwork_url") val artworkUrl: String = "",
    @ColumnInfo(name = "added_at") val addedAt: Instant = Instant.now(),
)
