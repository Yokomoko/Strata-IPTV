package com.strata.tv.data.epg

import android.util.Log
import com.strata.tv.data.db.ContentItemEntity
import com.strata.tv.data.db.ProgrammeDao
import com.strata.tv.domain.ChannelDeduplicator

/**
 * Reconciles M3U channel identifiers with XMLTV programme `channel`
 * attributes.
 *
 * Evolved from v1's `lib/services/epg_channel_matcher.dart` with
 * several additional heuristics to improve coverage:
 *
 * 1. Build normalised lookups from [ProgrammeDao.distinctChannelIds]
 *    and, when available, from XMLTV `<channel>` display names stored
 *    in [xmltvDisplayNames].
 * 2. For a given [ContentItemEntity], try — in order:
 *    a. Verbatim tvg-id exact match.
 *    b. Normalised tvg-id.
 *    c. Normalised tvg-name.
 *    d. Cleaned display name.
 *    e. Suffix-stripped match (remove HD/FHD/4K/UK/US/+1 etc.).
 *    f. Alias-expanded match (BBC One -> BBC 1, etc.).
 *    g. Broadcaster-root match (strip country/region prefix from both sides).
 * 3. A confidence threshold prevents false positives: very short
 *    normalised keys (< 4 chars after stripping) are rejected from
 *    fuzzy steps to avoid "BBC" matching "BBC News".
 *
 * Construction is `suspend` because it needs the distinct channel ids
 * from Room; once built, [resolve] is a pure in-memory map lookup.
 */
class EpgChannelMatcher private constructor(
    /** Normalised XMLTV id -> original XMLTV id. */
    private val byNormalisedId: Map<String, String>,
    /** Normalised XMLTV display name -> original XMLTV id. */
    private val byNormalisedName: Map<String, String>,
    /** Suffix-stripped normalised id -> original XMLTV id. */
    private val bySuffixStripped: Map<String, String>,
    /** Alias-expanded normalised id -> original XMLTV id. */
    private val byAlias: Map<String, String>,
    /** Total XMLTV channel ids indexed (for coverage reports). */
    val xmltvChannelCount: Int,
) {

    companion object {
        private const val TAG = "EpgChannelMatcher"
        private const val MAX_LOGGED_MISSES = 20

        /**
         * Minimum normalised-key length for fuzzy matching steps
         * (suffix-stripped, alias, broadcaster-root).  Keys shorter
         * than this are too ambiguous — "sky" could match "Sky News",
         * "Sky Sports", "Sky Atlantic" etc.
         */
        private const val MIN_FUZZY_KEY_LENGTH = 4

        /**
         * Build a matcher from the distinct XMLTV channel ids currently
         * stored in the programmes table.
         *
         * @param xmltvDisplayNames Optional map of XMLTV channel-id to
         *   display name, parsed from `<channel>` elements.  When
         *   provided this dramatically improves matching because many
         *   XMLTV ids are opaque numeric strings but the display names
         *   are human-readable.
         */
        suspend fun build(
            programmeDao: ProgrammeDao,
            xmltvDisplayNames: Map<String, String> = emptyMap(),
        ): EpgChannelMatcher {
            val channelIds = programmeDao.distinctChannelIds()
            Log.d(TAG, "Building matcher from ${channelIds.size} distinct XMLTV channel ids")
            if (channelIds.isNotEmpty()) {
                Log.d(TAG, "Sample XMLTV ids: ${channelIds.take(10)}")
            }
            if (xmltvDisplayNames.isNotEmpty()) {
                Log.d(
                    TAG,
                    "XMLTV display names available: ${xmltvDisplayNames.size} " +
                        "(sample: ${xmltvDisplayNames.entries.take(5).joinToString { "${it.key}=${it.value}" }})",
                )
            }

            val byId = mutableMapOf<String, String>()
            val byName = mutableMapOf<String, String>()
            val bySuffix = mutableMapOf<String, String>()
            val byAliasMap = mutableMapOf<String, String>()

            for (id in channelIds) {
                if (id.isEmpty()) continue

                // --- Primary: normalised id ---
                val normId = normaliseId(id)
                if (normId.isNotEmpty()) byId.putIfAbsent(normId, id)

                // --- Name extracted from id (non-numeric ids) ---
                val nameFromId = nameFromId(id)
                if (nameFromId.isNotEmpty()) byName.putIfAbsent(nameFromId, id)

                // --- Suffix-stripped variant ---
                val stripped = stripQualitySuffixes(normId)
                if (stripped.isNotEmpty() && stripped != normId) {
                    bySuffix.putIfAbsent(stripped, id)
                }

                // --- Alias-expanded variants ---
                for (alias in expandAliases(normId)) {
                    byAliasMap.putIfAbsent(alias, id)
                }
                for (alias in expandAliases(stripped)) {
                    byAliasMap.putIfAbsent(alias, id)
                }
            }

            // --- Index XMLTV display names ---
            for ((xmltvId, displayName) in xmltvDisplayNames) {
                if (displayName.isBlank()) continue
                // Only index if we actually have programmes for this channel.
                if (xmltvId !in channelIds) continue

                val normDisplay = normaliseName(displayName)
                if (normDisplay.isNotEmpty()) {
                    byName.putIfAbsent(normDisplay, xmltvId)
                }
                val strippedDisplay = stripQualitySuffixes(normDisplay)
                if (strippedDisplay.isNotEmpty() && strippedDisplay != normDisplay) {
                    bySuffix.putIfAbsent(strippedDisplay, xmltvId)
                }
                for (alias in expandAliases(normDisplay)) {
                    byAliasMap.putIfAbsent(alias, xmltvId)
                }
            }

            Log.d(
                TAG,
                "Indexed ${byId.size} by-id, ${byName.size} by-name, " +
                    "${bySuffix.size} by-suffix, ${byAliasMap.size} by-alias",
            )
            return EpgChannelMatcher(byId, byName, bySuffix, byAliasMap, channelIds.size)
        }

        // -----------------------------------------------------------------
        // Normalisation helpers — lower-case, strip punctuation, collapse.
        // -----------------------------------------------------------------

        /**
         * Normalise an id-shaped string.
         * `bbc.one.uk` -> `bbconeuk`, `UK_ITV_1_HD` -> `ukitv1hd`.
         *
         * Note: this does NOT strip suffixes — it only lowercases and
         * removes punctuation/whitespace.  Suffix stripping is done
         * separately in [stripQualitySuffixes] so we can index both
         * the full normalised form and the stripped form.
         */
        internal fun normaliseId(raw: String): String {
            return raw.lowercase()
                .replace(Regex("""[.\-_/:\s]"""), "")
        }

        /**
         * Normalise a human-readable channel name: lowercase, remove
         * punctuation, collapse whitespace.
         */
        internal fun normaliseName(raw: String): String {
            return raw.lowercase()
                .replace(Regex("""[.\-_/:\s&']"""), "")
        }

        /**
         * Strip common quality, country, and region suffixes that
         * appear on one side but not the other.
         *
         * Applied as a separate step so we can index both the full
         * normalised form and the stripped variant.
         */
        internal fun stripQualitySuffixes(normalised: String): String {
            // Loop because country and quality suffixes can interleave
            // (e.g., "bbconeukfhd" needs quality stripped first to
            // expose the country suffix, then country stripped).
            var result = normalised
            var prev: String
            do {
                prev = result
                result = result
                    .replace(Regex("""(?:uk|us|gb|ie|ca|au)+$"""), "")
                    .replace(Regex("""(?:hd|fhd|uhd|4k|sd|hevc|h265|1080p|720p)+$"""), "")
                    .replace(Regex("""(?:\+1|\+2|\+24)$"""), "")
            } while (result != prev)
            return result
        }

        /**
         * Generate alias variants of a normalised key by applying
         * common channel name equivalences:
         * - BBC One / BBC 1, ITV 1 / ITV1, Channel 4 / Channel Four, etc.
         *
         * Returns a set of aliases (may be empty if no aliases apply).
         */
        private fun expandAliases(normalised: String): Set<String> {
            val aliases = mutableSetOf<String>()
            val replacements = listOf(
                "bbcone" to "bbc1",
                "bbc1" to "bbcone",
                "bbctwo" to "bbc2",
                "bbc2" to "bbctwo",
                "bbcthree" to "bbc3",
                "bbc3" to "bbcthree",
                "bbcfour" to "bbc4",
                "bbc4" to "bbcfour",
                "itv1" to "itv",
                "itv" to "itv1",
                "channelfour" to "channel4",
                "channel4" to "channelfour",
                "channelfive" to "channel5",
                "channel5" to "channelfive",
                "five" to "channel5",
                "channel5" to "five",
                "5star" to "channel5star",
                "5usa" to "channel5usa",
            )
            for ((from, to) in replacements) {
                if (normalised.startsWith(from)) {
                    aliases.add(normalised.replaceFirst(from, to))
                }
            }
            return aliases
        }

        /**
         * Extract a guessed channel name from an XMLTV channel id.
         *
         * XMLTV ids frequently encode a readable name:
         *   `bbc.one.uk` -> `bbconeuk`
         *   `UK_ITV_1_HD` -> `ukitv1hd`
         *   `10009.sd.org` -> `""` (numeric provider id, not reversible)
         *
         * Returns empty when the id looks numeric/opaque.
         */
        private fun nameFromId(id: String): String {
            val normalised = normaliseId(id)
            if (normalised.length < 3) return ""
            // Reject pure numeric ids (provider-assigned opaque ids).
            if (normalised.matches(Regex("""^\d+$"""))) return ""
            // Also reject ids that are mostly numeric with a short suffix
            // (e.g., "10009sd" from "10009.sd.org").
            if (normalised.matches(Regex("""^\d{3,}\w{0,3}$"""))) return ""
            return normalised
        }

        /**
         * Extract the "broadcaster root" from a normalised key.
         *
         * Strips leading country prefixes (uk, us, gb, ie, etc.) and
         * trailing quality/region suffixes, leaving just the core
         * broadcaster name.
         *
         * `ukbbconehduk` -> `bbcone`
         * `usespn2hd`    -> `espn2`
         */
        internal fun broadcasterRoot(normalised: String): String {
            var key = normalised
            // Strip leading country codes.
            key = key.replaceFirst(Regex("""^(?:uk|us|gb|ie|ca|au|nz)"""), "")
            // Strip trailing quality + country suffixes.
            key = stripQualitySuffixes(key)
            return key
        }
    }

    private val loggedMisses = mutableSetOf<String>()
    private var totalResolved = 0
    private var totalMissed = 0

    /** Summary stats for diagnostic logging after a full resolve pass. */
    fun coverageSummary(): String =
        "Resolved $totalResolved, missed $totalMissed of ${totalResolved + totalMissed} " +
            "(${if (totalResolved + totalMissed > 0) (totalResolved * 100 / (totalResolved + totalMissed)) else 0}% coverage)"

    /**
     * Resolve the XMLTV channel id for a given content item.
     *
     * Returns null if no reasonable match is found.  Try order:
     *   1. Verbatim tvg-id exact match.
     *   2. Normalised tvg-id in the id map.
     *   3. Normalised tvg-id in the name map (cross-lookup).
     *   4. Normalised tvg-name in the name map.
     *   5. Cleaned display name in the name map.
     *   6. Suffix-stripped normalised tvg-id / name / display name.
     *   7. Alias-expanded match.
     *   8. Broadcaster-root match (strip country prefix from both sides).
     */
    fun resolve(channel: ContentItemEntity): String? {
        val result = resolveInternal(channel)
        if (result != null) {
            totalResolved++
        } else {
            totalMissed++
            logMiss(channel)
        }
        return result
    }

    private fun resolveInternal(channel: ContentItemEntity): String? {
        // --- Step 1: Verbatim tvg-id (exact match in the raw id set) ---
        if (channel.tvgId.isNotEmpty()) {
            val verbatim = byNormalisedId[channel.tvgId.lowercase().replace(Regex("""[.\-_/:\s]"""), "")]
            if (verbatim != null) return verbatim
        }

        // --- Step 2: Normalised tvg-id in the id map ---
        val normTvgId = if (channel.tvgId.isNotEmpty()) normaliseId(channel.tvgId) else ""
        if (normTvgId.isNotEmpty()) {
            byNormalisedId[normTvgId]?.let { return it }
            // Cross-lookup: try the tvg-id in the name map too.
            byNormalisedName[normTvgId]?.let { return it }
        }

        // --- Step 3: Normalised tvg-name ---
        val normTvgName = if (channel.tvgName.isNotEmpty()) normaliseName(channel.tvgName) else ""
        if (normTvgName.isNotEmpty()) {
            byNormalisedName[normTvgName]?.let { return it }
            byNormalisedId[normTvgName]?.let { return it }
        }

        // --- Step 4: Cleaned display name ---
        val cleaned = ChannelDeduplicator.cleanChannelName(channel.displayName)
        val normDisplay = if (cleaned.isNotEmpty()) normaliseName(cleaned) else ""
        if (normDisplay.isNotEmpty()) {
            byNormalisedName[normDisplay]?.let { return it }
            byNormalisedId[normDisplay]?.let { return it }
        }

        // --- Step 5: Suffix-stripped match ---
        // Try all candidate keys with quality/country suffixes removed.
        for (key in listOfNotNull(
            normTvgId.takeIf { it.isNotEmpty() },
            normTvgName.takeIf { it.isNotEmpty() },
            normDisplay.takeIf { it.isNotEmpty() },
        )) {
            val stripped = stripQualitySuffixes(key)
            if (stripped.length >= MIN_FUZZY_KEY_LENGTH && stripped != key) {
                bySuffixStripped[stripped]?.let { return it }
                // Also check if the stripped key exists in the primary maps.
                byNormalisedId[stripped]?.let { return it }
                byNormalisedName[stripped]?.let { return it }
            }
        }

        // --- Step 6: Alias-expanded match ---
        for (key in listOfNotNull(
            normTvgId.takeIf { it.isNotEmpty() },
            normTvgName.takeIf { it.isNotEmpty() },
            normDisplay.takeIf { it.isNotEmpty() },
        )) {
            for (alias in expandAliases(key)) {
                if (alias.length >= MIN_FUZZY_KEY_LENGTH) {
                    byAlias[alias]?.let { return it }
                    byNormalisedId[alias]?.let { return it }
                    byNormalisedName[alias]?.let { return it }
                }
            }
            // Also try alias of the suffix-stripped form.
            val stripped = stripQualitySuffixes(key)
            if (stripped.length >= MIN_FUZZY_KEY_LENGTH) {
                for (alias in expandAliases(stripped)) {
                    if (alias.length >= MIN_FUZZY_KEY_LENGTH) {
                        byAlias[alias]?.let { return it }
                        byNormalisedId[alias]?.let { return it }
                        byNormalisedName[alias]?.let { return it }
                    }
                }
            }
        }

        // --- Step 7: Broadcaster-root match ---
        // Strip leading country prefix + trailing quality suffix from
        // both the M3U key and the XMLTV keys.  Only if the root is
        // long enough to be unambiguous.
        for (key in listOfNotNull(
            normTvgId.takeIf { it.isNotEmpty() },
            normTvgName.takeIf { it.isNotEmpty() },
            normDisplay.takeIf { it.isNotEmpty() },
        )) {
            val root = broadcasterRoot(key)
            if (root.length >= MIN_FUZZY_KEY_LENGTH) {
                // Check suffix-stripped map (where XMLTV broadcaster roots live).
                bySuffixStripped[root]?.let { return it }
                byNormalisedId[root]?.let { return it }
                byNormalisedName[root]?.let { return it }
                // Try aliases of the root.
                for (alias in expandAliases(root)) {
                    if (alias.length >= MIN_FUZZY_KEY_LENGTH) {
                        bySuffixStripped[alias]?.let { return it }
                        byNormalisedId[alias]?.let { return it }
                        byNormalisedName[alias]?.let { return it }
                    }
                }
            }
        }

        return null
    }

    private fun logMiss(channel: ContentItemEntity) {
        if (loggedMisses.size >= MAX_LOGGED_MISSES) return
        val key = if (channel.tvgId.isNotEmpty()) {
            "tvg:${channel.tvgId}"
        } else {
            "name:${channel.displayName}"
        }
        if (!loggedMisses.add(key)) return
        Log.d(
            TAG,
            "MISS tvgId=\"${channel.tvgId}\" " +
                "tvgName=\"${channel.tvgName}\" " +
                "display=\"${channel.displayName}\" " +
                "normId=\"${normaliseId(channel.tvgId)}\" " +
                "normName=\"${normaliseName(
                    ChannelDeduplicator.cleanChannelName(channel.displayName),
                )}\"",
        )
    }
}
