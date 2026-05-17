package com.strata.tv.wizard

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.strata.tv.data.settings.SettingsRepository
import com.strata.tv.testing.FakeXtreamServer
import com.strata.tv.testing.resetForTest
import com.strata.tv.ui.setup.SetupState
import com.strata.tv.ui.setup.SetupViewModel
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

/**
 * End-to-end test of the setup-wizard ViewModel against a real
 * Hilt-injected [SettingsRepository] and a [FakeXtreamServer] standing
 * in for MyBunny.TV's `player_api.php`.
 *
 * Wizard flow now has three steps:
 *  1. Pick provider
 *  2. Credentials  ->  submitCredentials() tests + advances to step 3
 *  3. Filters      ->  finish() persists provider + filter choices
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class SetupViewModelTest {

    @get:Rule val hilt = HiltAndroidRule(this)

    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var xtreamApi: com.strata.tv.data.settings.XtreamApi

    private lateinit var fakeServer: FakeXtreamServer
    private lateinit var viewModel: SetupViewModel

    @Before
    fun setup() {
        hilt.inject()
        settings.resetForTest()
        fakeServer = FakeXtreamServer().apply { start() }
        viewModel = SetupViewModel(settings, xtreamApi)
    }

    @After
    fun teardown() {
        fakeServer.shutdown()
    }

    @Test
    fun submitCredentials_withEmptyFields_setsErrorMessage() = runTest {
        viewModel.selectProvider("custom_m3u")
        // No URL entered.

        viewModel.submitCredentials()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertThat(state.errorMessage).isNotNull()
        assertThat(state.step).isEqualTo(SetupViewModel.Step.Credentials)
    }

    @Test
    fun customM3uHappyPath_advancesToFiltersAndFinishes() = runTest {
        viewModel.selectProvider("custom_m3u")
        viewModel.updateField(
            SetupViewModel.Field.CustomM3uUrl,
            "http://example.com/playlist.m3u",
        )

        // Step 2 -> 3
        viewModel.submitCredentials()
        advanceUntilIdle()
        val afterCreds = viewModel.state.first { it.step == SetupViewModel.Step.Filters }
        assertThat(afterCreds.errorMessage).isNull()

        // Step 3 -> done
        viewModel.finish()
        val finalState = viewModel.state.first { it.finished }
        assertThat(finalState.finished).isTrue()

        // Persisted: provider + default UK + English filters.
        val saved = settings.current()
        assertThat(saved.provider.providerId).isEqualTo("custom_m3u")
        assertThat(saved.provider.customM3uUrl).isEqualTo("http://example.com/playlist.m3u")
        assertThat(saved.countryWhitelist).contains("UK")
        assertThat(saved.wantedLanguages).contains("en")
    }

    @Test
    fun xtreamGoodCreds_advancesToFilters() = runTest {
        fakeServer.authenticated = true
        viewModel.selectProvider("custom_xtream")
        viewModel.updateField(SetupViewModel.Field.Host, fakeServer.host())
        viewModel.updateField(SetupViewModel.Field.Username, "test")
        viewModel.updateField(SetupViewModel.Field.Password, "secret")

        viewModel.submitCredentials()
        advanceUntilIdle()

        val afterCreds = viewModel.state.first { it.step == SetupViewModel.Step.Filters }
        assertThat(afterCreds.errorMessage).isNull()
    }

    @Test
    fun xtreamBadCreds_staysOnCredentialsWithError() = runTest {
        fakeServer.authenticated = false
        viewModel.selectProvider("custom_xtream")
        viewModel.updateField(SetupViewModel.Field.Host, fakeServer.host())
        viewModel.updateField(SetupViewModel.Field.Username, "test")
        viewModel.updateField(SetupViewModel.Field.Password, "wrong")

        viewModel.submitCredentials()
        advanceUntilIdle()

        val s = viewModel.state.first { it.errorMessage != null || it.step == SetupViewModel.Step.Filters }
        assertThat(s.step).isEqualTo(SetupViewModel.Step.Credentials)
        assertThat(s.errorMessage).isNotNull()
        assertThat(s.errorMessage).contains("authenticate")
    }

    @Test
    fun filterToggle_addsAndRemovesItem() = runTest {
        viewModel.selectProvider("custom_m3u")
        viewModel.updateField(SetupViewModel.Field.CustomM3uUrl, "http://x.com/p.m3u")
        viewModel.submitCredentials()
        advanceUntilIdle()

        // Untick UK
        val before: SetupState = viewModel.state.first { it.step == SetupViewModel.Step.Filters }
        assertThat(before.countries).contains("UK")
        viewModel.toggleCountry("UK")
        assertThat(viewModel.state.value.countries).doesNotContain("UK")

        // Re-tick
        viewModel.toggleCountry("UK")
        assertThat(viewModel.state.value.countries).contains("UK")
    }

    @Test
    fun deselectAllLanguages_clearsThenSelectAllRestores() = runTest {
        viewModel.selectProvider("custom_m3u")
        viewModel.updateField(SetupViewModel.Field.CustomM3uUrl, "http://x.com/p.m3u")
        viewModel.submitCredentials()
        advanceUntilIdle()

        viewModel.deselectAllLanguages()
        assertThat(viewModel.state.value.languages).isEmpty()

        viewModel.selectAllLanguages(setOf("en", "fr", "de"))
        assertThat(viewModel.state.value.languages).containsExactly("en", "fr", "de")
    }
}
