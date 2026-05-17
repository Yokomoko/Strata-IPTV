package com.strata.tv.domain

import com.strata.tv.data.db.MovieListItem
import com.strata.tv.data.db.SeriesEntity
import com.strata.tv.ui.movies.GenreRail
import com.strata.tv.ui.shows.ShowGenreRail

/**
 * Shared genre-rail grouping used by [MoviesViewModel] and
 * [ShowsViewModel].
 *
 * Previously the algorithm was copy-pasted across both ViewModels with
 * identical split delimiters, trim/filter logic, `>= 3` threshold, and
 * `take(15)` limit.  Unifying here:
 *
 * 1. removes drift risk (if the split behaviour changes in one place it
 *    now changes in both), and
 * 2. gives us a single place to run the work on [Dispatchers.Default]
 *    instead of blocking the Room invalidation thread.
 *
 * Split delimiters match TMDB's formatting quirks: commas, pipes, and
 * forward slashes all appear in real payloads.
 */
object GenreGrouper {

    /** Minimum entries required to surface a rail — below this we hide the rail. */
    private const val MIN_RAIL_SIZE = 3

    /** Hard cap on number of rails — anything past this is visual noise. */
    private const val MAX_RAILS = 15

    private val SPLIT_DELIMITERS = charArrayOf(',', '|', '/')

    /**
     * Group [items] by primary genre (the first token of the genre
     * string after splitting on `,`, `|`, or `/`).  Items with no
     * parseable genre land in "Uncategorised".  Rails with fewer than
     * [MIN_RAIL_SIZE] entries are dropped.  Final list is sorted by
     * size desc + capped at [MAX_RAILS].
     */
    private inline fun <T> groupByPrimaryGenre(
        items: List<T>,
        genreOf: (T) -> String,
    ): List<Pair<String, List<T>>> {
        val byGenre = HashMap<String, MutableList<T>>()
        for (item in items) {
            val genre = genreOf(item).splitToSequence(*SPLIT_DELIMITERS)
                .map { it.trim() }
                .firstOrNull { it.isNotBlank() }
                ?: "Uncategorised"
            byGenre.getOrPut(genre) { ArrayList() }.add(item)
        }
        return byGenre.entries
            .asSequence()
            .filter { it.value.size >= MIN_RAIL_SIZE }
            .sortedWith(
                compareBy<Map.Entry<String, MutableList<T>>> { it.key == "Uncategorised" }
                    .thenByDescending { it.value.size },
            )
            .take(MAX_RAILS)
            .map { it.key to it.value }
            .toList()
    }

    /** Group [movies] into [GenreRail]s for the Movies screen. */
    fun group(movies: List<MovieListItem>): List<GenreRail> =
        groupByPrimaryGenre(movies) { it.genre }
            .map { (genre, items) -> GenreRail(genre, items) }

    /** Group [series] into [ShowGenreRail]s for the Shows screen. */
    fun groupSeries(series: List<SeriesEntity>): List<ShowGenreRail> =
        groupByPrimaryGenre(series) { it.genre }
            .map { (genre, items) -> ShowGenreRail(genre, items) }
}
