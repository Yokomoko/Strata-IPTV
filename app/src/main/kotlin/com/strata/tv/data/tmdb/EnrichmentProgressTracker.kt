package com.strata.tv.data.tmdb

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Shared progress tracker for TMDB enrichment.
 *
 * Both [MovieEnrichmentService] and [SeriesEnrichmentService] report
 * into this singleton so the UI can show a single combined progress
 * indicator (circular ring + percentage in the sidebar).
 */
@Singleton
class EnrichmentProgressTracker @Inject constructor() {

    data class Progress(
        val processed: Int = 0,
        val total: Int = 0,
        val isRunning: Boolean = false,
    ) {
        val fraction: Float
            get() = if (total > 0) (processed.toFloat() / total).coerceIn(0f, 1f) else 0f
        val percent: Int
            get() = (fraction * 100).toInt()
    }

    private val _progress = MutableStateFlow(Progress())
    val progress: StateFlow<Progress> = _progress.asStateFlow()

    /** Call before starting a batch — adds to the total work count. */
    fun addWork(count: Int) {
        _progress.update {
            it.copy(total = it.total + count, isRunning = true)
        }
    }

    /** Call after each item is processed. */
    fun advance() {
        _progress.update { it.copy(processed = it.processed + 1) }
    }

    /** Call when all enrichment is done. */
    fun finish() {
        _progress.update { it.copy(isRunning = false) }
    }

    /** Reset counters for a fresh run. */
    fun reset() {
        _progress.value = Progress()
    }
}
