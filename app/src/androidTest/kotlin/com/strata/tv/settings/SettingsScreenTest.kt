package com.strata.tv.settings

import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.strata.tv.data.settings.SettingsRepository
import com.strata.tv.data.settings.SyncFrequency
import com.strata.tv.testing.HiltTestActivity
import com.strata.tv.testing.resetForTest
import com.strata.tv.ui.settings.SettingsScreen
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

/**
 * Compose UI tests for [SettingsScreen].  Drives the real screen via
 * [createAndroidComposeRule] with [HiltTestActivity] so the Hilt-injected
 * [com.strata.tv.ui.settings.SettingsViewModel] resolves end-to-end.
 *
 * State assertions probe persisted [com.strata.tv.data.settings.AppSettings]
 * via `runBlocking { settings.current() }` because the on-screen styling
 * (selected = bold + checkmark prefix) is harder to introspect than
 * the underlying DataStore record.
 *
 * Scenarios:
 *  - All section headers render: Library, Provider, Sync, Content filters,
 *    Playback, About.
 *  - Switching sync frequency from the default (Daily) to "Every launch"
 *    flips the persisted [SyncFrequency] value.
 *  - Toggling the "Stop stream when browsing menus" row writes the new
 *    boolean to [SettingsRepository].
 *  - Tapping an excluded-category chip removes that entry from
 *    [com.strata.tv.data.settings.AppSettings.excludedCategories].
 *
 * Note: SettingsScreen does *not* expose region/country chips, language
 * filters, or "Select all" / "Deselect all" controls — those weren't
 * implemented when these tests were written.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class SettingsScreenTest {

    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<HiltTestActivity>()

    @Inject lateinit var settings: SettingsRepository

    @Before
    fun setup() {
        hilt.inject()
        settings.resetForTest()
    }

    /** Render the SettingsScreen inside the test activity. */
    private fun launch() {
        compose.activity.runOnUiThread {
            compose.activity.setContent { SettingsScreen() }
        }
    }

    @Test
    fun settings_rendersAllSectionHeaders() {
        launch()

        // Every SectionHeader from SettingsScreen.kt should be on-screen.
        // (The screen is verticalScroll, but at default test density the
        // first paint already lays out the whole column.)
        compose.onNodeWithText("Library").assertIsDisplayed()
        compose.onNodeWithText("Provider").assertIsDisplayed()
        compose.onNodeWithText("Sync").assertIsDisplayed()
        compose.onNodeWithText("Content filters").assertIsDisplayed()
        compose.onNodeWithText("Playback").assertIsDisplayed()
        compose.onNodeWithText("About").assertIsDisplayed()
    }

    @Test
    fun syncFrequency_clickingEveryLaunch_persistsSelection() {
        // Default is Daily — resetForTest() set this explicitly.
        assertThat(runBlocking { settings.current().syncFrequency })
            .isEqualTo(SyncFrequency.Daily)

        launch()

        // "Every launch" is the headline of the EveryLaunch ListRow.
        compose.onNodeWithText("Every launch").performClick()

        // Wait for the DataStore write to land (settings.current() is
        // a suspending call; runBlocking polls until we see the new value).
        val persisted = runBlocking {
            var attempts = 0
            var current = settings.current().syncFrequency
            while (current != SyncFrequency.EveryLaunch && attempts < 50) {
                kotlinx.coroutines.delay(20)
                current = settings.current().syncFrequency
                attempts++
            }
            current
        }
        assertThat(persisted).isEqualTo(SyncFrequency.EveryLaunch)
    }

    @Test
    fun playbackToggle_clickingStopStream_flipsPersistedBoolean() {
        // Default — resetForTest() sets stopStreamInMenus = false.
        assertThat(runBlocking { settings.current().stopStreamInMenus }).isFalse()

        launch()

        // The Playback row headline is "Stop stream when browsing menus".
        compose.onNodeWithText("Stop stream when browsing menus").performClick()

        val persisted = runBlocking {
            var attempts = 0
            var current = settings.current().stopStreamInMenus
            while (!current && attempts < 50) {
                kotlinx.coroutines.delay(20)
                current = settings.current().stopStreamInMenus
                attempts++
            }
            current
        }
        assertThat(persisted).isTrue()
    }

    @Test
    fun exclusionEditor_clickingChip_removesThatEntry() {
        // The defaults (reapplied by resetForTest) contain "XXX" — the
        // ExclusionEditor renders one chip per excluded entry, and
        // tapping a chip *removes* it (see ExclusionEditor in
        // SettingsScreen.kt — onClick = onChange(current - entry)).
        val before = runBlocking { settings.current().excludedCategories }
        assertThat(before).contains("XXX")

        launch()

        // The chip label is "${entry}  ×".  Match by entry text via
        // substring matcher.  onAllNodes is safer than onNode because
        // the headline "Excluded categories" supporting line also
        // references the entries — pick the first match (the chip).
        compose.onAllNodesWithText("XXX  ×", substring = false).onFirst().performClick()

        // Poll until "XXX" disappears from the persisted set.
        val after = runBlocking {
            var attempts = 0
            var current = settings.current().excludedCategories
            while (current.contains("XXX") && attempts < 50) {
                kotlinx.coroutines.delay(20)
                current = settings.current().excludedCategories
                attempts++
            }
            current
        }
        assertThat(after).doesNotContain("XXX")
        // Other defaults should remain.
        assertThat(after).contains("Religious")
    }
}

