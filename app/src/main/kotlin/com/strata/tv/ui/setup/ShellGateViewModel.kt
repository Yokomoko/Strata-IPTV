package com.strata.tv.ui.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.strata.tv.data.db.MovieDao
import com.strata.tv.data.repo.SyncService
import com.strata.tv.data.settings.SettingsRepository
import com.strata.tv.data.tmdb.EnrichmentProgressTracker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
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
    movieDao: MovieDao,
) : ViewModel() {

    private val backgroundedFirstSync = MutableStateFlow(false)

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
    ) { snapshot, backgrounded, syncProgress, enrich, movieCount ->
        val isConfigured = snapshot.provider.isConfigured
        val syncDone = syncProgress is SyncService.Progress.Done
        // Enrichment is "done" when:
        //   - it has been kicked off at least once (`enrichmentHasStarted`)
        //     AND it's no longer running (the tracker calls `finish()`
        //     when there's no work left), OR
        //   - the first sync never queued any enrichment work at all
        //     (e.g. an empty catalogue), so we shouldn't wait for it.
        val enrichDone = !enrich.isRunning &&
            (enrich.enrichmentHasStarted || syncDone && syncProgress.totalParsed == 0)
        // Returning users who already have content in the library
        // should never see the "Building your Library" screen on
        // launch — they have data, just let them straight into the
        // shell and let the periodic sync update the library in the
        // background.  Only show FirstSync when the library is
        // genuinely empty.
        val hasContent = movieCount > 0
        when {
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
}

enum class GateStage { Wizard, FirstSync, Main }
