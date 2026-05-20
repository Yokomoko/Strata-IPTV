package com.strata.tv.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore("strata_settings")

/**
 * Persistent app settings backed by [androidx.datastore.preferences].
 *
 * Credentials, provider host, and sync preferences live here — never in
 * source.  The repository exposes [settings] as a hot [Flow] so the
 * Settings screen and SyncWorker react to changes immediately.
 */
@Singleton
class SettingsRepository @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
) {

    private object Keys {
        val PROVIDER_ID = stringPreferencesKey("provider_id")
        val PROVIDER_HOST = stringPreferencesKey("provider_host")
        val PROVIDER_USERNAME = stringPreferencesKey("provider_username")
        val PROVIDER_PASSWORD = stringPreferencesKey("provider_password")
        val PROVIDER_CUSTOM_M3U = stringPreferencesKey("provider_custom_m3u")
        val PROVIDER_USE_FILTERED = booleanPreferencesKey("provider_use_filtered_playlist")
        val SYNC_FREQUENCY = stringPreferencesKey("sync_frequency")
        val LAST_SYNC_EPOCH_DAY = longPreferencesKey("last_sync_epoch_day")
        val COUNTRY_WHITELIST = stringSetPreferencesKey("country_whitelist")
        val EXCLUDED_CATEGORIES = stringSetPreferencesKey("excluded_categories")
        val WANTED_LANGUAGES = stringSetPreferencesKey("wanted_languages")
        val EXCLUDED_GENRES = stringSetPreferencesKey("excluded_genres")
        val EXCLUDED_LANGUAGES = stringSetPreferencesKey("excluded_languages")
        val MINIMUM_YEAR = androidx.datastore.preferences.core.intPreferencesKey("minimum_year")
        val BLACKLIST = stringSetPreferencesKey("blacklist")
        val STOP_STREAM_IN_MENUS = booleanPreferencesKey("stop_stream_in_menus")
    }

    /** Hot [Flow] of the full settings state. */
    val settings: Flow<AppSettings> = context.settingsStore.data.map { prefs ->
        AppSettings(
            provider = ProviderConfig(
                providerId = prefs[Keys.PROVIDER_ID].orEmpty(),
                host = prefs[Keys.PROVIDER_HOST].orEmpty(),
                username = prefs[Keys.PROVIDER_USERNAME].orEmpty(),
                password = prefs[Keys.PROVIDER_PASSWORD].orEmpty(),
                customM3uUrl = prefs[Keys.PROVIDER_CUSTOM_M3U].orEmpty(),
                useFilteredPlaylist = prefs[Keys.PROVIDER_USE_FILTERED] ?: false,
            ),
            syncFrequency = prefs[Keys.SYNC_FREQUENCY]
                ?.let { runCatching { SyncFrequency.valueOf(it) }.getOrNull() }
                ?: SyncFrequency.Daily,
            countryWhitelist = prefs[Keys.COUNTRY_WHITELIST]
                ?: AppSettings.DEFAULT_COUNTRY_WHITELIST,
            excludedCategories = prefs[Keys.EXCLUDED_CATEGORIES]
                ?: AppSettings.DEFAULT_EXCLUDED_CATEGORIES,
            wantedLanguages = prefs[Keys.WANTED_LANGUAGES]
                ?: AppSettings.DEFAULT_WANTED_LANGUAGES,
            excludedGenres = prefs[Keys.EXCLUDED_GENRES] ?: emptySet(),
            excludedLanguages = prefs[Keys.EXCLUDED_LANGUAGES] ?: emptySet(),
            minimumYear = prefs[Keys.MINIMUM_YEAR] ?: AppSettings.DEFAULT_MINIMUM_YEAR,
            blacklistedContentIds = prefs[Keys.BLACKLIST] ?: emptySet(),
            stopStreamInMenus = prefs[Keys.STOP_STREAM_IN_MENUS] ?: false,
        )
    }

    /** One-shot snapshot. */
    suspend fun current(): AppSettings = settings.first()

    suspend fun setProvider(config: ProviderConfig) {
        context.settingsStore.edit { prefs ->
            prefs[Keys.PROVIDER_ID] = config.providerId
            prefs[Keys.PROVIDER_HOST] = config.host
            prefs[Keys.PROVIDER_USERNAME] = config.username
            prefs[Keys.PROVIDER_PASSWORD] = config.password
            prefs[Keys.PROVIDER_CUSTOM_M3U] = config.customM3uUrl
            prefs[Keys.PROVIDER_USE_FILTERED] = config.useFilteredPlaylist
        }
    }

    suspend fun clearProvider() {
        context.settingsStore.edit { prefs ->
            prefs.remove(Keys.PROVIDER_ID)
            prefs.remove(Keys.PROVIDER_HOST)
            prefs.remove(Keys.PROVIDER_USERNAME)
            prefs.remove(Keys.PROVIDER_PASSWORD)
            prefs.remove(Keys.PROVIDER_CUSTOM_M3U)
            prefs.remove(Keys.PROVIDER_USE_FILTERED)
        }
    }

    suspend fun setSyncFrequency(value: SyncFrequency) {
        context.settingsStore.edit { it[Keys.SYNC_FREQUENCY] = value.name }
    }

    suspend fun setLastSyncEpochDay(epochDay: Long) {
        context.settingsStore.edit { it[Keys.LAST_SYNC_EPOCH_DAY] = epochDay }
    }

    suspend fun lastSyncEpochDay(): Long =
        context.settingsStore.data.first()[Keys.LAST_SYNC_EPOCH_DAY] ?: Long.MIN_VALUE

    suspend fun setCountryWhitelist(values: Set<String>) {
        context.settingsStore.edit { it[Keys.COUNTRY_WHITELIST] = values }
    }

    suspend fun setExcludedCategories(values: Set<String>) {
        context.settingsStore.edit { it[Keys.EXCLUDED_CATEGORIES] = values }
    }

    suspend fun setWantedLanguages(values: Set<String>) {
        context.settingsStore.edit { it[Keys.WANTED_LANGUAGES] = values }
    }

    suspend fun addExcludedGenre(genre: String) {
        val trimmed = genre.trim()
        if (trimmed.isEmpty()) return
        context.settingsStore.edit { prefs ->
            val current = prefs[Keys.EXCLUDED_GENRES] ?: emptySet()
            prefs[Keys.EXCLUDED_GENRES] = current + trimmed
        }
    }

    suspend fun removeExcludedGenre(genre: String) {
        context.settingsStore.edit { prefs ->
            val current = prefs[Keys.EXCLUDED_GENRES] ?: emptySet()
            prefs[Keys.EXCLUDED_GENRES] = current - genre
        }
    }

    suspend fun addExcludedLanguage(code: String) {
        val trimmed = code.trim()
        if (trimmed.isEmpty()) return
        context.settingsStore.edit { prefs ->
            val current = prefs[Keys.EXCLUDED_LANGUAGES] ?: emptySet()
            prefs[Keys.EXCLUDED_LANGUAGES] = current + trimmed
        }
    }

    suspend fun removeExcludedLanguage(code: String) {
        context.settingsStore.edit { prefs ->
            val current = prefs[Keys.EXCLUDED_LANGUAGES] ?: emptySet()
            prefs[Keys.EXCLUDED_LANGUAGES] = current - code
        }
    }

    suspend fun setMinimumYear(year: Int) {
        context.settingsStore.edit { it[Keys.MINIMUM_YEAR] = year }
    }

    suspend fun addToBlacklist(contentId: String) {
        context.settingsStore.edit { prefs ->
            val current = prefs[Keys.BLACKLIST] ?: emptySet()
            prefs[Keys.BLACKLIST] = current + contentId
        }
    }

    suspend fun removeFromBlacklist(contentId: String) {
        context.settingsStore.edit { prefs ->
            val current = prefs[Keys.BLACKLIST] ?: emptySet()
            prefs[Keys.BLACKLIST] = current - contentId
        }
    }

    suspend fun setStopStreamInMenus(value: Boolean) {
        context.settingsStore.edit { it[Keys.STOP_STREAM_IN_MENUS] = value }
    }
}
