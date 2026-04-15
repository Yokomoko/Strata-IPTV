package com.strata.tv.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room migrations.
 *
 * We keep these alongside a `fallbackToDestructiveMigration()` safety net
 * on the database builder so a genuinely broken migration doesn't brick
 * the app, but the happy path should always run through a real migration
 * so user data (favourites, watchlist, continue-watching, last-watched
 * channel) is preserved across app updates.
 */

/**
 * v3 → v4: adds two hot-path indices without touching existing data.
 *
 * 1. `movies(hidden, year)` — covers `WHERE hidden = 0 ORDER BY year DESC`
 *    used across Home rails, hero candidates, and Movies screen.
 * 2. `channels(is_favourite)` — fav-mode zapping does this filter on
 *    every D-pad press; a partial-covering index turns a full table
 *    scan of 1000+ channels into an indexed lookup.
 *
 * The index names mirror Room's own naming convention
 * (`index_<table>_<columns>`) so Room's schema validator doesn't
 * complain about a mismatch with the generated schema file.
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_movies_hidden_year` " +
                "ON `movies` (`hidden`, `year`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_channels_is_favourite` " +
                "ON `channels` (`is_favourite`)",
        )
    }
}
