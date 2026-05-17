package com.strata.tv.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.strata.tv.data.db.ChannelDao
import com.strata.tv.data.repo.SyncService
import com.strata.tv.data.settings.SettingsRepository
import com.strata.tv.testing.FakeXtreamServer
import com.strata.tv.testing.resetForTest
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

/**
 * End-to-end test of the sync pipeline's exclusion filter.
 * Feeds [SyncService] a fake M3U from [FakeXtreamServer] containing an
 * "XXX" channel group and verifies those entries don't land in Room.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ExclusionFilterTest {

    @get:Rule val hilt = HiltAndroidRule(this)

    @Inject lateinit var syncService: SyncService
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var channelDao: ChannelDao

    private lateinit var fakeServer: FakeXtreamServer

    @Before
    fun setup() {
        hilt.inject()
        settings.resetForTest()
        fakeServer = FakeXtreamServer().apply {
            m3uBody = """
                #EXTM3U
                #EXTINF:-1 tvg-id="bbc1" tvg-name="BBC One" group-title="UK | Entertainment",BBC One HD
                http://example.com/bbc1
                #EXTINF:-1 tvg-id="xxx1" tvg-name="Adult 1" group-title="XXX",Adult Channel 1
                http://example.com/xxx1
                #EXTINF:-1 tvg-id="cnn" tvg-name="CNN" group-title="US | News",CNN HD
                http://example.com/cnn
                #EXTINF:-1 tvg-id="xxx2" tvg-name="Adult 2" group-title="For Adults",Adult Channel 2
                http://example.com/xxx2
            """.trimIndent()
            start()
        }
    }

    @After fun teardown() { fakeServer.shutdown() }

    @Test
    fun sync_filtersOutExcludedGroups() = runTest {
        // Defaults already exclude "XXX" and "Adult"-style groups.
        syncService.syncFromUrl("${fakeServer.host()}/get.php", sourceId = 1)

        val channels = channelDao.watchAll().first()
        val names = channels.map { it.contentId }

        // BBC + CNN should be present; XXX/Adult ones should NOT.
        assertThat(channels).hasSize(2)
        assertThat(names.any { it.contains("Adult", ignoreCase = true) }).isFalse()
    }

    @Test
    fun sync_withEmptyExclusionList_keepsEverything() = runTest {
        settings.setExcludedCategories(emptySet())
        settings.setCountryWhitelist(emptySet())

        syncService.syncFromUrl("${fakeServer.host()}/get.php", sourceId = 1)

        val channels = channelDao.watchAll().first()
        assertThat(channels).hasSize(4)
    }
}
