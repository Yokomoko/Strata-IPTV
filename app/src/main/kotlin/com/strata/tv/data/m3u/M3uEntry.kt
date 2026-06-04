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
    /**
     * Alternate stream URLs for the SAME logical item at lower quality,
     * ordered next-best → worst.  Xtream `get_series_info` commonly
     * returns 4K + 1080p + 720p sources per episode; [streamUrl] is the
     * best one and these are the fallbacks the player drops to when the
     * best one can't be decoded (e.g. 4K HEVC on a 1080p Fire Stick).
     */
    val altStreamUrls: List<String> = emptyList(),
    /**
     * Provider-supplied poster URL (Xtream `stream_icon`).  Used to fill
     * the movie's poster instantly at sync — ~90% of mybunny's catalogue
     * carries one — so the library isn't blank while TMDB enrichment
     * runs in the background.  Distinct from [tvgLogo] (which is the
     * channel/EPG logo); for VOD they're the same source but movies read
     * this into `movies.poster_url`.
     */
    val posterUrl: String = "",
    /** Provider-supplied rating (Xtream `rating`), 0–10.  Filled at sync. */
    val rating: Double? = null,
)
