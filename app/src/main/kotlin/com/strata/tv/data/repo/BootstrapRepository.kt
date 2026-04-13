package com.strata.tv.data.repo

import com.strata.tv.AppConfig
import com.strata.tv.data.db.SourceDao
import com.strata.tv.data.db.SourceEntity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ensures the app has a row in the `sources` table for the user's
 * playlist URL, creating one on first launch.  Returns the
 * `sources.id` integer that subsequent sync calls reference.
 *
 * For now that's literally always one row, hard-coded from
 * [AppConfig].  A future settings screen could mutate it.
 */
@Singleton
class BootstrapRepository @Inject constructor(
    private val sourceDao: SourceDao,
) {
    suspend fun ensureSource(): Int {
        val existing = sourceDao.all().firstOrNull()
        if (existing != null) return existing.id
        return sourceDao.insert(
            SourceEntity(
                playlistUrl = AppConfig.PLAYLIST_URL,
                epgUrl = AppConfig.EPG_URL,
                userAgent = "Strata TV",
            ),
        ).toInt()
    }
}
