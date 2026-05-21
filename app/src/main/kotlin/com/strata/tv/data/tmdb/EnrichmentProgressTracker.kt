package com.strata.tv.data.tmdb

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Unified progress tracker for sync + enrichment.
 *
 * Shows a single percentage in the sidebar ring. During sync, it
 * tracks parsed items / estimated total. When enrichment starts, it
 * resets to track enriched items / items needing enrichment.
 */
@Singleton
class EnrichmentProgressTracker @Inject constructor() {

    data class Progress(
        val processed: Int = 0,
        val total: Int = 0,
        val isRunning: Boolean = false,
        val label: String = "",
        /**
         * Set to true the first time [startEnrichment] runs with a
         * non-zero work total this process lifetime.  The first-sync
         * gate needs this so it doesn't transition to the main shell
         * before TMDB has had a chance to populate posters and titles.
         * Once true, stays true — there's no resetting after a sync.
         */
        val enrichmentHasStarted: Boolean = false,
        /**
         * Most recent enrichment failure message, or `null` when
         * everything is fine.  Surfaced in the sync progress screen
         * and in the sidebar ring tooltip so users see "TMDB enrichment
         * failed: …" instead of an unchanging spinner (issue #47).
         */
        val errorMessage: String? = null,
    ) {
        val fraction: Float
            get() = if (total > 0) (processed.toFloat() / total).coerceIn(0f, 1f) else 0f
        val percent: Int
            get() = (fraction * 100).toInt()
    }

    private val _progress = MutableStateFlow(Progress())
    val progress: StateFlow<Progress> = _progress.asStateFlow()

    /** Start the sync phase with an estimated total. */
    fun startSync(estimatedTotal: Int = 0) {
        _progress.value = Progress(
            processed = 0,
            total = estimatedTotal,
            isRunning = true,
            label = "Syncing",
        )
    }

    /** Update sync progress. Grows the estimate as more batches arrive. */
    fun syncAdvance(batchSize: Int) {
        _progress.update {
            val newProcessed = it.processed + batchSize
            // If no fixed total yet, estimate 4x what we've seen so far.
            val est = if (it.total == 0) (newProcessed * 4).coerceAtLeast(1000) else it.total
            it.copy(processed = newProcessed, total = est, isRunning = true)
        }
    }

    /** Lock in the final sync total (from ParseResult.Complete).
     *  Don't set processed = total yet — persistence still needs to run.
     *  Cap at 90% so the ring doesn't flash 100% prematurely. */
    fun syncComplete(totalParsed: Int) {
        _progress.update {
            // Hold the ring at 90% during the persistence phase so it
            // doesn't appear "Done" while sync is still committing rows.
            // startEnrichment() will reset to 0/enrichmentTotal afterwards,
            // and finish() lands at 100%.
            it.copy(
                processed = (totalParsed * 9 / 10).coerceAtLeast(0),
                total = totalParsed.coerceAtLeast(1),
                label = "Processing",
            )
        }
    }

    /** Switch to enrichment phase — resets counters.
     *  If total is 0, nothing to enrich — hide the ring. */
    fun startEnrichment(total: Int) {
        if (total == 0) {
            _progress.update { it.copy(isRunning = false) }
            return
        }
        _progress.update {
            it.copy(
                processed = 0,
                total = total,
                isRunning = true,
                label = "Enriching",
                enrichmentHasStarted = true,
            )
        }
    }

    /** Enrichment batch discovered — adds to the total and re-enables ring. */
    fun addWork(count: Int) {
        if (count <= 0) return
        _progress.update {
            it.copy(
                total = it.total + count,
                isRunning = true,
                enrichmentHasStarted = true,
            )
        }
    }

    /** One item enriched. */
    fun advance() {
        _progress.update { it.copy(processed = it.processed + 1) }
    }

    /** All done. */
    fun finish() {
        _progress.update { it.copy(isRunning = false) }
    }

    /**
     * Override the ring's status label without changing the counters.
     * Used by the JSON sync path to surface phases like
     * "Loading episodes (1200 / 4881)" while the same ring continues
     * to tick along.
     */
    fun setLabel(label: String) {
        _progress.update { it.copy(label = label, isRunning = true) }
    }

    /**
     * Record an enrichment failure so the UI can show "enrichment
     * failed: …" instead of leaving the user staring at a spinner.
     * Called from [com.strata.tv.ui.home.HomeViewModel] when one of the
     * `enrichBatch()` calls throws.
     */
    fun markError(message: String) {
        _progress.update { it.copy(errorMessage = message, isRunning = false) }
    }

    /** Full reset. */
    fun reset() {
        _progress.value = Progress()
    }
}
