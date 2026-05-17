package com.strata.tv.ui.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.strata.tv.data.repo.SyncService
import com.strata.tv.data.settings.SettingsRepository
import com.strata.tv.data.tmdb.EnrichmentProgressTracker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Decides what the user sees at app start:
 *   - [GateStage.Wizard]      — provider not configured yet
 *   - [GateStage.FirstSync]   — provider just configured, library
 *                                still empty, sync may be running
 *   - [GateStage.Main]        — past first sync OR user chose to
 *                                let sync run in the background
 *
 * The first-sync screen is dismissable per-launch via [skipToBackground].
 */
@HiltViewModel
class ShellGateViewModel @Inject constructor(
    settings: SettingsRepository,
    val syncService: SyncService,
    val enrichmentTracker: EnrichmentProgressTracker,
) : ViewModel() {

    private val backgroundedFirstSync = MutableStateFlow(false)

    val stage: StateFlow<GateStage?> = combine(
        settings.settings,
        backgroundedFirstSync,
        syncService.progress,
    ) { snapshot, backgrounded, progress ->
        val isConfigured = snapshot.provider.isConfigured
        val firstSyncDone = progress is SyncService.Progress.Done
        when {
            !isConfigured -> GateStage.Wizard
            firstSyncDone || backgrounded -> GateStage.Main
            else -> GateStage.FirstSync
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = null,
    )

    fun skipToBackground() {
        backgroundedFirstSync.value = true
    }
}

enum class GateStage { Wizard, FirstSync, Main }
