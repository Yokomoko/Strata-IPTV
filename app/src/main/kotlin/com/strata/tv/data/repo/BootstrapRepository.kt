package com.strata.tv.data.repo

import com.strata.tv.AppConfig
import com.strata.tv.data.db.SourceDao
import com.strata.tv.data.db.SourceEntity
import com.strata.tv.data.settings.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ensures the app has a row in the `sources` table for the user's
 * playlist URL.  The URL itself comes from [SettingsRepository] —
 * never hard-coded — and may be empty until the first-run wizard
 * has been completed.
 *
 * The `sources` row is reused across syncs and its `playlist_url`
 * column is updated when the user changes provider in Settings.
 */
@Singleton
class BootstrapRepository @Inject constructor(
    private val sourceDao: SourceDao,
    private val settings: SettingsRepository,
) {
    /**
     * Resolve the source row id, creating one if it doesn't exist.
     * Always rewrites the playlist URL so a credentials change in
     * Settings takes effect on the next sync without leaking the
     * previous URL.
     */
    suspend fun ensureSource(): Int {
        val current = settings.current()
        val playlistUrl = if (current.provider.isConfigured) current.provider.toM3uUrl() else ""
        val existing = sourceDao.all().firstOrNull()
        if (existing != null) {
            // Keep the row id stable; refresh the URL if it has changed.
            if (existing.playlistUrl != playlistUrl) {
                sourceDao.insert(existing.copy(playlistUrl = playlistUrl))
            }
            return existing.id
        }
        return sourceDao.insert(
            SourceEntity(
                playlistUrl = playlistUrl,
                epgUrl = AppConfig.EPG_URL,
                userAgent = "Strata TV",
            ),
        ).toInt()
    }
}
