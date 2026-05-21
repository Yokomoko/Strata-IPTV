package com.strata.tv.ui.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.strata.tv.data.db.MovieDao
import com.strata.tv.data.repo.BootstrapRepository
import com.strata.tv.data.repo.SyncService
import com.strata.tv.data.settings.SettingsRepository
import com.strata.tv.data.tmdb.EnrichmentProgressTracker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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
    private val settings: SettingsRepository,
    val syncService: SyncService,
    val enrichmentTracker: EnrichmentProgressTracker,
    movieDao: MovieDao,
    private val bootstrap: BootstrapRepository,
) : ViewModel() {

    private val backgroundedFirstSync = MutableStateFlow(false)

    /**
     * Forces the gate back to [GateStage.Wizard] regardless of whether
     * the provider is configured.  Set by [backToWizard] so the user
     * can re-enter credentials after a sync error without us deleting
     * their existing provider config.
     */
    private val forceWizard = MutableStateFlow(false)

    /**
     * True if the user has been through the [GateStage.Wizard] or
     * [GateStage.FirstSync] screens at any point this process lifetime.
     *
     * The Shell uses this to suppress the "Loading your library" splash
     * overlay on the very first run — the user has just sat through the
     * wizard and the sync progress screen, so dropping them straight
     * into a populating home screen is a much smoother transition than
     * a fresh splash that adds another 8 seconds before anything
     * useful appears.
     */
    private val _passedThroughGate = MutableStateFlow(false)
    val passedThroughGate: StateFlow<Boolean> = _passedThroughGate.asStateFlow()

    val stage: StateFlow<GateStage?> = combine(
        settings.settings,
        backgroundedFirstSync,
        syncService.progress,
        enrichmentTracker.progress,
        movieDao.watchVisibleCount(),
        forceWizard,
    ) { flows ->
        val snapshot = flows[0] as com.strata.tv.data.settings.AppSettings
        val backgrounded = flows[1] as Boolean
        val syncProgress = flows[2] as SyncService.Progress
        val enrich = flows[3] as EnrichmentProgressTracker.Progress
        val movieCount = flows[4] as Int
        val force = flows[5] as Boolean
        val isConfigured = snapshot.provider.isConfigured
        val syncDone = syncProgress is SyncService.Progress.Done
        val enrichDone = !enrich.isRunning &&
            (enrich.enrichmentHasStarted || syncDone && syncProgress.totalParsed == 0)
        val hasContent = movieCount > 0
        when {
            force -> GateStage.Wizard
            !isConfigured -> GateStage.Wizard
            backgrounded -> GateStage.Main
            hasContent -> GateStage.Main
            syncDone && enrichDone -> GateStage.Main
            else -> GateStage.FirstSync
        }
    }
        .onEach { current ->
            if (current == GateStage.Wizard || current == GateStage.FirstSync) {
                _passedThroughGate.value = true
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null,
        )

    fun skipToBackground() {
        backgroundedFirstSync.value = true
    }

    /**
     * Re-trigger the sync from the FirstSync error screen.  Same code
     * path as SettingsViewModel.refreshLibrary() — pulls the M3U from
     * the configured provider, lets SyncService update progress, and
     * surfaces failures via Progress.Error again if it still fails.
     */
    fun retrySync() {
        viewModelScope.launch {
            val cfg = settings.settings.first().provider
            if (!cfg.isConfigured) return@launch
            val sourceId = bootstrap.ensureSource()
            runCatching {
                syncService.syncFromUrl(cfg.toM3uUrl(), sourceId)
            }.onFailure { e ->
                android.util.Log.e("ShellGateVM", "retrySync failed", e)
            }
        }
    }

    /**
     * Back out to the Setup Wizard from the FirstSync error screen.
     * Doesn't delete the provider config — just forces the gate to
     * show the wizard so the user can re-enter credentials or pick a
     * different provider.  When the wizard finishes [clearForceWizard]
     * lets the normal gate logic take over again.
     */
    fun backToWizard() {
        forceWizard.value = true
    }

    fun clearForceWizard() {
        forceWizard.value = false
    }
}

enum class GateStage { Wizard, FirstSync, Main }
