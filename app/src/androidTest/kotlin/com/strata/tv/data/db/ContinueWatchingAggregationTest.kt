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
import java.time.Instant
import javax.inject.Inject

/**
 * Verifies the schema-v6 [ContinueWatchingDao.watchAllGrouped] query:
 *
 * For show episodes the query collapses multiple entries of the same
 * series to one row (the most recent), so the Home rail shows
 * "Breaking Bad" once rather than every watched episode.  Movies and
 * live channels are unaffected.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ContinueWatchingAggregationTest {

    @get:Rule val hilt = HiltAndroidRule(this)

    @Inject lateinit var cwDao: ContinueWatchingDao

    @Before fun setup() { hilt.inject() }

    @Test
    fun watchAllGrouped_collapsesShowEpisodesPerSeries() = runTest {
        // Three episodes of Breaking Bad — different timestamps.
        cwDao.upsert(cwEntry(
            contentId = "Breaking Bad S1E1",
            seriesTitle = "Breaking Bad",
            season = 1, episode = 1,
            lastUpdated = Instant.parse("2026-01-01T00:00:00Z"),
        ))
        cwDao.upsert(cwEntry(
            contentId = "Breaking Bad S1E2",
            seriesTitle = "Breaking Bad",
            season = 1, episode = 2,
            lastUpdated = Instant.parse("2026-01-02T00:00:00Z"),
        ))
        cwDao.upsert(cwEntry(
            contentId = "Breaking Bad S2E5",
            seriesTitle = "Breaking Bad",
            season = 2, episode = 5,
            lastUpdated = Instant.parse("2026-01-03T00:00:00Z"),
        ))
        // One episode of a different series and one movie — should all survive.
        cwDao.upsert(cwEntry(
            contentId = "The Wire S1E1",
            seriesTitle = "The Wire",
            season = 1, episode = 1,
            lastUpdated = Instant.parse("2026-01-04T00:00:00Z"),
        ))
        cwDao.upsert(ContinueWatchingEntity(
            contentId = "Inception",
            contentType = "movie",
            resumePositionMs = 5_000L,
            lastUpdated = Instant.parse("2026-01-05T00:00:00Z"),
        ))

        val grouped = cwDao.watchAllGrouped().first()

        // 1 Breaking Bad row + 1 The Wire row + 1 Inception row.
        assertThat(grouped).hasSize(3)
        // The surviving Breaking Bad entry is the *most recent* episode (S2E5).
        val bb = grouped.first { it.seriesTitle == "Breaking Bad" }
        assertThat(bb.seasonNumber).isEqualTo(2)
        assertThat(bb.episodeNumber).isEqualTo(5)
        // Movies still show as-is.
        assertThat(grouped.any { it.contentId == "Inception" }).isTrue()
    }

    @Test
    fun watchAllGrouped_emptyDb_returnsEmpty() = runTest {
        assertThat(cwDao.watchAllGrouped().first()).isEmpty()
    }

    private fun cwEntry(
        contentId: String,
        seriesTitle: String,
        season: Int,
        episode: Int,
        lastUpdated: Instant,
    ) = ContinueWatchingEntity(
        contentId = contentId,
        contentType = "show",
        streamUrl = "http://example.com/$contentId",
        artworkUrl = "",
        resumePositionMs = 60_000L,
        totalDurationMs = 600_000L,
        lastUpdated = lastUpdated,
        seriesTitle = seriesTitle,
        seasonNumber = season,
        episodeNumber = episode,
    )
}
