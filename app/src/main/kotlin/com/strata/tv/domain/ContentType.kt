package com.strata.tv.domain

/**
 * Discriminator for an entry parsed out of the user's M3U playlist.
 *
 * Mirrors v1's `lib/core/utils/content_type.dart`.
 */
enum class ContentType {
    /** A linear / live channel (BBC One, Sky News, etc.). */
    Live,

    /** A movie video-on-demand entry. */
    Movie,

    /** A TV series episode video-on-demand entry. */
    Show,
}
