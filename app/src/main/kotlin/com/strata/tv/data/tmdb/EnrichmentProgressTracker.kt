package com.strata.tv.data.tmdb

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Unified progress tracker for the full sync + enrichment pipeline.
 *
 * The total is set once from the M3U playlist entry count (e.g. 60000).
 * Both sync parsing and TMDB enrichment call [advance] as they process
 * items. The UI shows a single percentage: processed / grandTotal.
 */
@Singleton
class EnrichmentProgressTracker @Inject constructor() {

    data class Progress(
        val processed: Int = 0,
        val grandTotal: Int = 0,
        val isRunning: Boolean = false,
    ) {
        val fraction: Float
            get() = if (grandTotal > 0) (processed.toFloat() / grandTotal).coerceIn(0f, 1f) else 0f
        val percent: Int
            get() = (fraction * 100).toInt()
    }

    private val _progress = MutableStateFlow(Progress())
    val progress: StateFlow<Progress> = _progress.asStateFlow()

    /** Set the grand total (full playlist size) once at sync start. */
    fun setGrandTotal(total: Int) {
        _progress.update { it.copy(grandTotal = total, isRunning = true) }
    }

    /** Call after each item is synced or enriched. */
    fun advance() {
        _progress.update { it.copy(processed = it.processed + 1) }
    }

    /** Advance by a batch count at once (for sync batch completions). */
    fun advanceBy(count: Int) {
        _progress.update { it.copy(processed = it.processed + count) }
    }

    /** No longer needed — kept for backward compat with enrichment services. */
    fun addWork(@Suppress("UNUSED_PARAMETER") count: Int) {
        // Grand total is set by setGrandTotal; individual addWork is a no-op now.
    }

    /** Call when all work is done. */
    fun finish() {
        _progress.update { it.copy(isRunning = false) }
    }

    /** Reset counters for a fresh run. */
    fun reset() {
        _progress.value = Progress()
    }
}
