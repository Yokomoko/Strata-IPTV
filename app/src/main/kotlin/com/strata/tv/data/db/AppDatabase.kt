package com.strata.tv.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * Strata TV's Room database.
 *
 * Schema version starts at 1 — we don't carry over v1's drift schema
 * versions (the file is a fresh sqlite file in a different package id
 * with a different name).  Future migrations bump from here.
 *
 * The DAO surface is intentionally split — one DAO per table family —
 * so each screen's ViewModel only depends on the queries it actually
 * uses.  Hilt provides the DAOs through a Module so callers never
 * touch [RoomDatabase] directly.
 */
@Database(
    entities = [
        SourceEntity::class,
        ContentItemEntity::class,
        ChannelEntity::class,
        MovieEntity::class,
        SeriesEntity::class,
        EpisodeEntity::class,
        ProgrammeEntity::class,
        ContinueWatchingEntity::class,
        WatchHistoryEntity::class,
        FavouriteEntity::class,
        WatchlistEntity::class,
    ],
    version = 8,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun contentDao(): ContentDao
    abstract fun channelDao(): ChannelDao
    abstract fun movieDao(): MovieDao
    abstract fun seriesDao(): SeriesDao
    abstract fun episodeDao(): EpisodeDao
    abstract fun programmeDao(): ProgrammeDao
    abstract fun continueWatchingDao(): ContinueWatchingDao
    abstract fun watchHistoryDao(): WatchHistoryDao
    abstract fun favouriteDao(): FavouriteDao
    abstract fun watchlistDao(): WatchlistDao
    abstract fun sourceDao(): SourceDao
}
