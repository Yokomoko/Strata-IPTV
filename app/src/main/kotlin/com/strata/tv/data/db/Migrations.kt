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

/**
 * v4 → v5: adds series context columns to `continue_watching` so the
 * show detail screen can determine which episode was last watched and
 * offer "Continue S2E3" / "Play Next Episode" prompts.
 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE continue_watching ADD COLUMN series_title TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE continue_watching ADD COLUMN season_number INTEGER DEFAULT NULL")
        db.execSQL("ALTER TABLE continue_watching ADD COLUMN episode_number INTEGER DEFAULT NULL")
    }
}

/**
 * v5 → v6: adds `last_seen_total_episodes` to the `series` table so we
 * can render a "NEW" badge when a sync increases a series' episode
 * count.  Default `0` means existing rows will *all* light up as having
 * new episodes on first launch after the migration — this is mostly
 * harmless: visiting any show clears its badge immediately via
 * `seriesDao.markEpisodesSeen`, and a fresh user's library has no
 * watchlist/CW entries yet so the new-episodes rail stays empty.
 */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE series ADD COLUMN last_seen_total_episodes INTEGER NOT NULL DEFAULT 0",
        )
    }
}
