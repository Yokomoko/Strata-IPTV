package com.strata.tv.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.strata.tv.data.db.ChannelDao
import com.strata.tv.data.db.MovieDao
import com.strata.tv.data.db.SeriesDao
import com.strata.tv.data.repo.BootstrapRepository
import com.strata.tv.data.repo.SyncService
import com.strata.tv.data.settings.AppSettings
import com.strata.tv.data.settings.ProviderConfig
import com.strata.tv.data.settings.SettingsRepository
import com.strata.tv.data.settings.SyncFrequency
import com.strata.tv.data.settings.XtreamApi
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val movieDao: MovieDao,
    private val seriesDao: SeriesDao,
    private val channelDao: ChannelDao,
    private val syncService: SyncService,
    private val bootstrap: BootstrapRepository,
    private val settingsRepo: SettingsRepository,
    private val xtreamApi: XtreamApi,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsRepo.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    val state: StateFlow<SettingsUiState> = combine(
        movieDao.watchVisibleCount(),
        seriesDao.watchCount(),
        channelDao.watchAll(),
    ) { movies, series, channels ->
        SettingsUiState(
            movieCount = movies,
            seriesCount = series,
            channelCount = channels.size,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SettingsUiState.Empty,
    )

    val syncProgress: StateFlow<SyncService.Progress> = syncService.progress

    private val _accountInfo = MutableStateFlow<XtreamApi.AccountInfo?>(null)
    val accountInfo: StateFlow<XtreamApi.AccountInfo?> = _accountInfo.asStateFlow()

    init {
        // Refresh account info whenever the provider config changes.
        viewModelScope.launch {
            settings.collect { snapshot ->
                _accountInfo.value = if (snapshot.provider.isConfigured) {
                    xtreamApi.accountInfo(snapshot.provider)
                } else null
            }
        }
    }

    fun refreshLibrary() {
        viewModelScope.launch {
            val cfg = settings.value.provider
            if (!cfg.isConfigured) return@launch
            val sourceId = bootstrap.ensureSource()
            // SyncService internally surfaces failures via its own
            // Progress.Error state — the UI subscribes to that and
            // renders an error banner.  We still log here as well so
            // any failure shows up in adb logcat for diagnosis (#47).
            runCatching {
                syncService.syncFromUrl(cfg.toM3uUrl(), sourceId)
                settingsRepo.setLastSyncEpochDay(Instant.now().epochSecond / 86_400)
            }.onFailure { e ->
                android.util.Log.e("SettingsVM", "refreshLibrary failed", e)
            }
        }
    }

    fun saveProvider(config: ProviderConfig) {
        viewModelScope.launch { settingsRepo.setProvider(config) }
    }

    fun clearProvider() {
        viewModelScope.launch { settingsRepo.clearProvider() }
    }

    /**
     * Toggle the "use website-curated filtered playlist" flag on the
     * current provider config and persist it.  Triggers a re-sync on
     * next launch (or via "Refresh library now") so the new URL is
     * actually exercised.
     */
    fun setUseFilteredPlaylist(value: Boolean) {
        viewModelScope.launch {
            val current = settingsRepo.current().provider
            settingsRepo.setProvider(current.copy(useFilteredPlaylist = value))
        }
    }

    fun setSyncFrequency(value: SyncFrequency) {
        viewModelScope.launch { settingsRepo.setSyncFrequency(value) }
    }

    fun setCountryWhitelist(values: Set<String>) {
        viewModelScope.launch { settingsRepo.setCountryWhitelist(values) }
    }

    fun setExcludedCategories(values: Set<String>) {
        viewModelScope.launch { settingsRepo.setExcludedCategories(values) }
    }

    fun setWantedLanguages(values: Set<String>) {
        viewModelScope.launch { settingsRepo.setWantedLanguages(values) }
    }

    fun setStopStreamInMenus(value: Boolean) {
        viewModelScope.launch { settingsRepo.setStopStreamInMenus(value) }
    }

    suspend fun testProvider(config: ProviderConfig): Boolean =
        xtreamApi.testConnection(config)
}

data class SettingsUiState(
    val movieCount: Int,
    val seriesCount: Int,
    val channelCount: Int,
) {
    val totalCount: Int get() = movieCount + seriesCount + channelCount

    companion object {
        val Empty = SettingsUiState(
            movieCount = 0,
            seriesCount = 0,
            channelCount = 0,
        )
    }
}
