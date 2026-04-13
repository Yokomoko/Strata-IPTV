package com.strata.tv.domain

/**
 * Levenshtein distance and fuzzy scoring — ported from
 * `lib/core/utils/fuzzy_match.dart` in the Flutter v1 app.
 *
 * Used by the search screen to rank results after the Room
 * `LIKE`-based SQL query returns candidate rows.  The SQL
 * pre-filter keeps the set small (capped at 200); this code
 * does the fine-grained ranking on that reduced set.
 */
object FuzzyMatch {

    /**
     * Classic Levenshtein distance — minimum single-character edits
     * (insert, delete, replace) needed to transform [a] into [b].
     *
     * Uses the standard two-row optimisation for O(min(m,n)) space.
     */
    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length

        var prev = IntArray(b.length + 1) { it }
        var curr = IntArray(b.length + 1)

        for (i in 1..a.length) {
            curr[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(
                    curr[j - 1] + 1,     // insert
                    prev[j] + 1,         // delete
                    prev[j - 1] + cost,  // replace
                )
            }
            val temp = prev
            prev = curr
            curr = temp
        }
        return prev[b.length]
    }

    /**
     * Check if every significant word in [query] fuzzy-matches at
     * least one word in [title] (within [maxDistance] edits).
     *
     * Words shorter than 3 characters are skipped — they're usually
     * articles ("a", "of", "the") that add noise.
     */
    private fun fuzzyWordMatch(
        query: String,
        title: String,
        maxDistance: Int = 2,
    ): Boolean {
        val queryWords = query.lowercase().split(Regex("\\s+"))
        val titleWords = title.lowercase().split(Regex("\\s+"))

        for (qw in queryWords) {
            if (qw.length < 3) continue
            var matched = false
            for (tw in titleWords) {
                if (tw.contains(qw) || qw.contains(tw)) {
                    matched = true
                    break
                }
                // Scale tolerance by word length — short words get less slack.
                val maxDist = if (qw.length <= 4) 1 else maxDistance
                if (levenshtein(qw, tw) <= maxDist) {
                    matched = true
                    break
                }
            }
            if (!matched) return false
        }
        return true
    }

    /**
     * Score [target] against [query] on a 0.0..1.0 scale (higher = better match).
     *
     * Scoring tiers:
     * - 1.0  exact substring containment
     * - 0.85 starts-with match (query is a prefix of target)
     * - 0.7  all query tokens fuzzy-match title tokens within 1 edit
     * - 0.5  all query tokens fuzzy-match within 2 edits
     * - 0.3  partial token overlap (at least half of query words match)
     * - 0.0  no match
     */
    fun fuzzyScore(query: String, target: String): Double {
        if (query.isBlank() || target.isBlank()) return 0.0

        val q = query.lowercase().trim()
        val t = target.lowercase().trim()

        // Tier 1 — exact substring containment.
        if (t.contains(q)) return 1.0

        // Tier 1b — starts-with (query is a prefix of target).
        if (t.startsWith(q)) return 0.85

        // Tier 2 — tight fuzzy word match (1 edit per word).
        if (fuzzyWordMatch(q, t, maxDistance = 1)) return 0.7

        // Tier 3 — looser fuzzy word match (2 edits per word).
        if (fuzzyWordMatch(q, t, maxDistance = 2)) return 0.5

        // Tier 4 — partial token overlap: at least half of query words match.
        val queryWords = q.split(Regex("\\s+")).filter { it.length >= 3 }
        if (queryWords.isNotEmpty()) {
            val titleWords = t.split(Regex("\\s+"))
            var matched = 0
            for (qw in queryWords) {
                for (tw in titleWords) {
                    if (tw.contains(qw) || qw.contains(tw) || levenshtein(qw, tw) <= 2) {
                        matched++
                        break
                    }
                }
            }
            if (matched >= (queryWords.size + 1) / 2) return 0.3
        }

        return 0.0
    }
}
