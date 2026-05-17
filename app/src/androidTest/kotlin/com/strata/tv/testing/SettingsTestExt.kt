package com.strata.tv.testing

import com.strata.tv.data.settings.AppSettings
import com.strata.tv.data.settings.SettingsRepository
import com.strata.tv.data.settings.SyncFrequency
import kotlinx.coroutines.runBlocking

/**
 * Reset a [SettingsRepository]'s persisted state back to defaults.
 * DataStore files are shared across instrumented tests in the same
 * process, so each test should call this in `@Before` to avoid
 * cross-test contamination.
 */
fun SettingsRepository.resetForTest() = runBlocking {
    clearProvider()
    setSyncFrequency(SyncFrequency.Daily)
    setCountryWhitelist(emptySet())
    setExcludedCategories(AppSettings.DEFAULT_EXCLUDED_CATEGORIES)
    setWantedLanguages(emptySet())
    setStopStreamInMenus(false)
    setLastSyncEpochDay(Long.MIN_VALUE)
}
