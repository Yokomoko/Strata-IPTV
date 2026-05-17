package com.strata.tv.domain

import java.security.MessageDigest

/**
 * Generates the stable, opaque `content_id` we use as the primary key
 * on every channel / movie / show row.
 *
 * The id has to be stable across playlist refreshes — if the IPTV
 * provider re-uploads the playlist with the same channel under a
 * slightly different display name (or vice versa) we want the same
 * row in our DB so the user's favourites / continue-watching / watch
 * history don't get orphaned.
 *
 * The recipe (matches Strata v1):
 * 1. Concatenate `sourceKey | normalisedTitle | groupTitle |
 *    streamUrlBase` with a separator unlikely to appear in any field.
 * 2. SHA-1 the result, hex-encode, take the first 16 chars.
 *
 * `streamUrlBase` is the URL with any query string stripped — IPTV
 * providers love to append per-session tokens that change every
 * refresh.  Stripping them keeps the id stable.
 */
object ContentIdHasher {

    private const val SEP = "\u001F" // ASCII unit separator — never seen in real data.

    /**
     * @param sourceKey       Identifier for the playlist source — usually
     *                        the playlist URL's host or a stored source row id.
     * @param normalisedTitle The cleaned, title-cased version of the
     *                        display name (use [TitleParser.normalise]).
     * @param groupTitle      The M3U `group-title` for the entry.
     * @param streamUrl       The raw stream URL.
     */
    fun hash(
        sourceKey: String,
        normalisedTitle: String,
        groupTitle: String,
        streamUrl: String,
    ): String {
        val payload = listOf(
            sourceKey,
            normalisedTitle,
            groupTitle,
            stripQuery(streamUrl),
        ).joinToString(SEP)

        val digest = MessageDigest.getInstance("SHA-1")
            .digest(payload.toByteArray(Charsets.UTF_8))
        return digest.toHexString().substring(0, 16)
    }

    /**
     * Drop the query string and trailing slash so per-session tokens
     * (`?token=…`, `&t=…`) don't make every refresh produce a new id.
     */
    private fun stripQuery(url: String): String {
        val q = url.indexOf('?')
        val base = if (q >= 0) url.substring(0, q) else url
        return base.trimEnd('/')
    }

    @OptIn(ExperimentalStdlibApi::class)
    private fun ByteArray.toHexString(): String = joinToString("") {
        ((it.toInt() and 0xff)).toString(16).padStart(2, '0')
    }
}
