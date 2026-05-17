package com.strata.tv.data.db

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

/**
 * Verifies the schema-v6 [SeriesDao.watchNewEpisodeShows] query:
 * series whose `total_episodes` has grown since the user last
 * engaged AND that the user actually cares about (in watchlist
 * OR has a continue-watching row).
 *
 * After [SeriesDao.markEpisodesSeen] the badge clears.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class NewEpisodeDetectionTest {

    @get:Rule val hilt = HiltAndroidRule(this)

    @Inject lateinit var seriesDao: SeriesDao
    @Inject lateinit var watchlistDao: WatchlistDao
    @Inject lateinit var cwDao: ContinueWatchingDao

    @Before fun setup() { hilt.inject() }

    @Test
    fun newEpisode_inWatchlist_surfacedByWatchNewEpisodeShows() = runTest {
        // Series with 10 episodes; user has seen 8 → NEW badge applies.
        seriesDao.upsertAll(listOf(
            seriesEntity("Foundation", total = 10, seen = 8),
        ))
        // Add to watchlist so the user "cares".
        watchlistDao.add(WatchlistEntity(
            contentId = "Foundation",
            contentType = "show",
            title = "Foundation",
        ))

        val result = seriesDao.watchNewEpisodeShows().first()
        assertThat(result).hasSize(1)
        assertThat(result.first().seriesTitle).isEqualTo("Foundation")
        assertThat(result.first().hasNewEpisodes).isTrue()
    }

    @Test
    fun newEpisode_notTracked_filteredOut() = runTest {
        // Total > seen but NOT in watchlist or CW → should be hidden.
        seriesDao.upsertAll(listOf(
            seriesEntity("Random Show", total = 5, seen = 3),
        ))

        val result = seriesDao.watchNewEpisodeShows().first()
        assertThat(result).isEmpty()
    }

    @Test
    fun markEpisodesSeen_clearsBadge() = runTest {
        seriesDao.upsertAll(listOf(
            seriesEntity("Severance", total = 9, seen = 6),
        ))
        watchlistDao.add(WatchlistEntity(
            contentId = "Severance",
            contentType = "show",
            title = "Severance",
        ))

        // Before: surfaced.
        assertThat(seriesDao.watchNewEpisodeShows().first()).hasSize(1)

        // User opens the show detail screen → mark seen.
        seriesDao.markEpisodesSeen("Severance")

        // After: cleared.
        assertThat(seriesDao.watchNewEpisodeShows().first()).isEmpty()
    }

    private fun seriesEntity(title: String, total: Int, seen: Int) = SeriesEntity(
        seriesTitle = title,
        totalSeasons = 1,
        totalEpisodes = total,
        lastSeenTotalEpisodes = seen,
    )
}
