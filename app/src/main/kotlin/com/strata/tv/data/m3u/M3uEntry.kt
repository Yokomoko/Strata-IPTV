package com.strata.tv.data.m3u

import com.strata.tv.domain.ContentType

/**
 * A single entry parsed out of an M3U playlist.
 *
 * Mirrors v1's `M3uEntry` (lib/services/m3u_parser.dart).  Fields stay
 * named as in v1 so the port reads identically; field order follows
 * v1 too for ease of comparison.
 */
data class M3uEntry(
    val displayName: String,
    val streamUrl: String,
    val groupTitle: String,
    val tvgId: String,
    val tvgName: String,
    val tvgLogo: String,
    val tvgType: String,
    val extinfDuration: Int,
    val contentType: ContentType,
    val movieTitle: String? = null,
    val movieYear: Int? = null,
    val seriesTitle: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
)
