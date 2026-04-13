package com.strata.tv.data.epg

import android.util.Log
import com.strata.tv.data.db.ContentItemEntity
import com.strata.tv.data.db.ProgrammeDao
import com.strata.tv.domain.ChannelDeduplicator

/**
 * Reconciles M3U channel identifiers with XMLTV programme `channel`
 * attributes.
 *
 * Direct port of `lib/services/epg_channel_matcher.dart` from Strata
 * v1.  The algorithm is identical:
 *
 * 1. Build a normalised lookup from [ProgrammeDao.distinctChannelIds].
 * 2. For a given [ContentItemEntity], try verbatim tvg-id, then
 *    normalised tvg-id, then tvg-name, then cleaned display name.
 *
 * Construction is `suspend` because it needs the distinct channel ids
 * from Room; once built, [resolve] is a pure in-memory map lookup.
 */
class EpgChannelMatcher private constructor(
    /** Normalised XMLTV id -> original XMLTV id. */
    private val byNormalisedId: Map<String, String>,
    /** Normalised channel name (extracted from id) -> original XMLTV id. */
    private val byNormalisedName: Map<String, String>,
) {

    companion object {
        private const val TAG = "EpgChannelMatcher"
        private const val MAX_LOGGED_MISSES = 20

        /**
         * Build a matcher from the distinct XMLTV channel ids currently
         * stored in the programmes table.
         */
        suspend fun build(programmeDao: ProgrammeDao): EpgChannelMatcher {
            val channelIds = programmeDao.distinctChannelIds()
            Log.d(TAG, "Built from ${channelIds.size} distinct XMLTV channel ids")
            if (channelIds.isNotEmpty()) {
                Log.d(TAG, "Sample ids: ${channelIds.take(10)}")
            }

            val byId = mutableMapOf<String, String>()
            val byName = mutableMapOf<String, String>()

            for (id in channelIds) {
                if (id.isEmpty()) continue
                val normId = normaliseId(id)
                if (normId.isNotEmpty()) byId.putIfAbsent(normId, id)

                val name = nameFromId(id)
                if (name.isNotEmpty()) byName.putIfAbsent(name, id)
            }

            Log.d(TAG, "Indexed ${byId.size} by-id / ${byName.size} by-name")
            return EpgChannelMatcher(byId, byName)
        }

        // -----------------------------------------------------------------
        // Normalisation helpers — lower-case, strip punctuation, collapse.
        // Identical to v1's _normaliseId / _normaliseName / _nameFromId.
        // -----------------------------------------------------------------

        /**
         * Normalise an id-shaped string.
         * `bbc.one.uk` -> `bbcone`, `UK_ITV_1_HD` -> `itv1`.
         */
        private fun normaliseId(raw: String): String {
            return raw.lowercase()
                .replace(Regex("""[.\-_/:\s]"""), "")
                // Strip common country / quality suffixes that appear on
                // one side but not the other.
                .replace(Regex("""(?:uk|gb|hd|fhd|uhd|sd|hevc)+$"""), "")
        }

        /** Same rules as [normaliseId] so the two namespaces converge. */
        private fun normaliseName(raw: String): String = normaliseId(raw)

        /**
         * Extract a guessed channel name from an XMLTV channel id.
         *
         * XMLTV ids frequently encode a readable name:
         *   `bbc.one.uk` -> `bbcone`
         *   `UK_ITV_1_HD` -> `itv1`
         *   `10009.sd.org` -> `""` (numeric provider id, not reversible)
         *
         * Returns empty when the id looks numeric/opaque.
         */
        private fun nameFromId(id: String): String {
            val normalised = normaliseId(id)
            if (normalised.length < 3) return ""
            if (normalised.matches(Regex("""^\d+$"""))) return ""
            return normalised
        }
    }

    private val loggedMisses = mutableSetOf<String>()

    /**
     * Resolve the XMLTV channel id for a given content item.
     *
     * Returns null if no reasonable match is found.  Try order:
     *   1. M3U tvg-id, normalised.
     *   2. M3U tvg-name, normalised.
     *   3. Cleaned display name, normalised.
     */
    fun resolve(channel: ContentItemEntity): String? {
        // 1. Normalised tvg-id.
        if (channel.tvgId.isNotEmpty()) {
            val normTvgId = normaliseId(channel.tvgId)
            val hit = byNormalisedId[normTvgId]
            if (hit != null) return hit
        }

        // 2. Normalised tvg-name.
        if (channel.tvgName.isNotEmpty()) {
            val hit = byNormalisedName[normaliseName(channel.tvgName)]
            if (hit != null) return hit
        }

        // 3. Cleaned display name.
        val cleaned = ChannelDeduplicator.cleanChannelName(channel.displayName)
        if (cleaned.isNotEmpty()) {
            val hit = byNormalisedName[normaliseName(cleaned)]
            if (hit != null) return hit
        }

        logMiss(channel)
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
