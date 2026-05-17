package com.strata.tv.wizard

import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.strata.tv.data.settings.SettingsRepository
import com.strata.tv.testing.HiltTestActivity
import com.strata.tv.testing.resetForTest
import com.strata.tv.ui.setup.SetupWizardScreen
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

/**
 * Compose UI tests for [SetupWizardScreen].  Drives the real screen via
 * [createAndroidComposeRule] with [HiltTestActivity] so the Hilt-injected
 * [com.strata.tv.ui.setup.SetupViewModel] resolves end-to-end (real
 * [SettingsRepository], real [com.strata.tv.data.settings.XtreamApi]).
 *
 * These tests intentionally only exercise the *picker* and *credentials*
 * field rendering — they don't click "Connect and continue", which would
 * make a network call to the configured host.  ViewModel-level
 * credential-submission tests already live in [SetupViewModelTest].
 *
 * Scenarios:
 *  - Step 1 renders all built-in providers from
 *    [com.strata.tv.data.settings.BuiltInProviders.ALL].
 *  - Selecting a built-in Xtream provider advances to credentials with
 *    Username + Password fields (no Host — that's baked in for built-ins).
 *  - Selecting Custom Xtream reveals the Host field alongside Username +
 *    Password.
 *  - The Back row from credentials returns to the provider picker.
 *  - Selecting Custom M3U URL reveals only an M3U URL field (no
 *    Username / Password / Host).
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class SetupWizardScreenTest {

    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<HiltTestActivity>()

    @Inject lateinit var settings: SettingsRepository

    @Before
    fun setup() {
        hilt.inject()
        settings.resetForTest()
    }

    /**
     * Renders the wizard at the picker step.  Tests call this in their
     * first line — keeping it out of `@Before` so each test can choose
     * to do extra setup against [settings] before composition begins.
     */
    private fun launch() {
        compose.activity.runOnUiThread {
            compose.activity.setContent {
                SetupWizardScreen(onFinished = {})
            }
        }
    }

    @Test
    fun providerPicker_rendersAllBuiltInProviders() {
        launch()

        // Hero header should be visible.
        compose.onNodeWithText("Choose your IPTV provider").assertIsDisplayed()

        // Every entry from BuiltInProviders.ALL should appear by display name.
        compose.onNodeWithText("MyBunny.TV").assertIsDisplayed()
        compose.onNodeWithText("SkyGlass").assertIsDisplayed()
        compose.onNodeWithText("Custom Xtream").assertIsDisplayed()
        compose.onNodeWithText("Custom M3U URL").assertIsDisplayed()
    }

    @Test
    fun selectingBuiltInProvider_advancesToCredentialsWithUsernameAndPassword() {
        launch()

        compose.onNodeWithText("MyBunny.TV").performClick()

        // Credentials step renders Username + Password field labels.
        compose.onNodeWithText("Username").assertIsDisplayed()
        compose.onNodeWithText("Password").assertIsDisplayed()

        // Host field is NOT shown for built-in providers (it's baked into
        // BuiltInProviders.Entry.host).  Assert by negation — the label
        // would say "Host (e.g. http://provider.com)" for custom Xtream.
        compose.onAllNodesWithText("Host (e.g. http://provider.com)")
            .assertCountEquals(0)

        // Picker entries are gone now that we've advanced.
        compose.onAllNodesWithText("Custom M3U URL").assertCountEquals(0)
    }

    @Test
    fun customXtream_revealsHostField() {
        launch()

        compose.onNodeWithText("Custom Xtream").performClick()

        // Custom Xtream shows Host + Username + Password.
        compose.onNodeWithText("Host (e.g. http://provider.com)").assertIsDisplayed()
        compose.onNodeWithText("Username").assertIsDisplayed()
        compose.onNodeWithText("Password").assertIsDisplayed()
    }

    @Test
    fun backFromCredentials_returnsToProviderPicker() {
        launch()

        // Advance to credentials.
        compose.onNodeWithText("MyBunny.TV").performClick()
        // Sanity: we're on credentials.
        compose.onNodeWithText("Username").assertIsDisplayed()

        // Click the Back row.  Surface wraps a Text("← Back") — match on it.
        compose.onNodeWithText("← Back").performClick()

        // Picker is back, with every provider visible again.
        compose.onNodeWithText("Choose your IPTV provider").assertIsDisplayed()
        compose.onNodeWithText("MyBunny.TV").assertIsDisplayed()
        compose.onNodeWithText("SkyGlass").assertIsDisplayed()
        compose.onNodeWithText("Custom Xtream").assertIsDisplayed()
        compose.onNodeWithText("Custom M3U URL").assertIsDisplayed()
    }

    @Test
    fun customM3u_showsUrlFieldWithoutUsernameOrPassword() {
        launch()

        compose.onNodeWithText("Custom M3U URL").performClick()

        // Only the M3U URL field is shown — no credentials, no host.
        compose.onNodeWithText("M3U URL").assertIsDisplayed()
        compose.onAllNodesWithText("Username").assertCountEquals(0)
        compose.onAllNodesWithText("Password").assertCountEquals(0)
        compose.onAllNodesWithText("Host (e.g. http://provider.com)").assertCountEquals(0)
    }
}
