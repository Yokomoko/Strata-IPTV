package com.strata.tv.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.strata.tv.AppConfig
import com.strata.tv.data.db.ChannelDao
import com.strata.tv.data.db.MovieDao
import com.strata.tv.data.db.SeriesDao
import com.strata.tv.data.repo.BootstrapRepository
import com.strata.tv.data.repo.SyncService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val movieDao: MovieDao,
    private val seriesDao: SeriesDao,
    private val channelDao: ChannelDao,
    private val syncService: SyncService,
    private val bootstrap: BootstrapRepository,
) : ViewModel() {

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

    fun refreshLibrary() {
        viewModelScope.launch {
            val sourceId = bootstrap.ensureSource()
            runCatching { syncService.syncFromUrl(AppConfig.PLAYLIST_URL, sourceId) }
        }
    }
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
