package com.strata.tv.data.repo

import android.util.Log
import com.strata.tv.data.db.MovieDao
import com.strata.tv.data.db.SeriesDao
import com.strata.tv.data.settings.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Library-wide hide/ignore actions invoked from the long-press context
 * menu on movie and show cards.  Three flavours:
 *
 * - [hideItem] — hides one specific movie or show by `content_id` /
 *   `series_title`.  Cheap, immediate, no settings change.
 * - [ignoreGenre] — adds a TMDB genre token to [com.strata.tv.data.settings.AppSettings.excludedGenres]
 *   and immediately flips `hidden = 1` on every matching movie / series
 *   row so the rails update without waiting for a fresh sync.
 * - [ignoreLanguage] — adds a language code to
 *   [com.strata.tv.data.settings.AppSettings.excludedLanguages] and
 *   hides every matching row.  Solves the "weird foreign things still
 *   pop up" case where an item slipped past the
 *   [com.strata.tv.data.settings.AppSettings.wantedLanguages] whitelist
 *   at enrichment time.
 *
 * All three operations are persistent — the settings rules survive
 * across re-syncs so newly imported items in those genres / languages
 * also get hidden by the enrichment services.
 */
@Singleton
class LibraryFilterRepository @Inject constructor(
    private val movieDao: MovieDao,
    private val seriesDao: SeriesDao,
    private val settingsRepo: SettingsRepository,
) {
    companion object {
        private const val TAG = "LibraryFilterRepo"
    }

    suspend fun hideMovie(contentId: String) {
        movieDao.setHiddenByContentId(contentId, true)
    }

    suspend fun hideSeries(seriesTitle: String) {
        seriesDao.setHidden(seriesTitle, true)
    }

    /**
     * Hide everything matching [genre] (case-insensitive substring on
     * the comma-separated TMDB genre string) across both the movies
     * and series tables, and add the token to the persistent
     * excluded-genres list so future syncs respect it.
     */
    suspend fun ignoreGenre(genre: String) {
        val trimmed = genre.trim()
        if (trimmed.isEmpty()) return
        settingsRepo.addExcludedGenre(trimmed)
        val movies = movieDao.hideByGenre(trimmed)
        val series = seriesDao.hideByGenre(trimmed)
        Log.i(TAG, "Ignore genre '$trimmed': hid $movies movies, $series series")
    }

    /**
     * Hide everything in [languageCode] (TMDB ISO-639-1, e.g. `"ja"`)
     * and remember the exclusion for future enrichment passes.
     */
    suspend fun ignoreLanguage(languageCode: String) {
        val trimmed = languageCode.trim()
        if (trimmed.isEmpty()) return
        settingsRepo.addExcludedLanguage(trimmed)
        val movies = movieDao.hideByLanguage(trimmed)
        val series = seriesDao.hideByLanguage(trimmed)
        Log.i(TAG, "Ignore language '$trimmed': hid $movies movies, $series series")
    }
}
