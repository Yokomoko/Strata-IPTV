package com.strata.tv.data.settings

/**
 * All user-editable application settings.  Provider config lives here
 * (so credentials are NEVER in source), along with sync cadence and
 * the three filter dimensions that whittle a massive multi-country IPTV
 * playlist down to "stuff the user actually wants".
 */
data class AppSettings(
    val provider: ProviderConfig = ProviderConfig(),
    val syncFrequency: SyncFrequency = SyncFrequency.Daily,
    /**
     * Country prefix whitelist.  Extracted from M3U `group-title` like
     * `"UK | Entertainment"` → `"UK"`.  Empty == accept all countries.
     */
    val countryWhitelist: Set<String> = DEFAULT_COUNTRY_WHITELIST,
    /**
     * Category / genre keywords to exclude.  Matched case-insensitively
     * as substrings of the M3U `group-title`.
     */
    val excludedCategories: Set<String> = DEFAULT_EXCLUDED_CATEGORIES,
    /**
     * Language codes (ISO-639-1) we want to keep for movies + series.
     * Filter runs against TMDB `original_language` after enrichment.
     * `""` covers entries TMDB hasn't tagged with a language.
     * Empty (no entries) == accept all languages.
     */
    val wantedLanguages: Set<String> = DEFAULT_WANTED_LANGUAGES,
    val blacklistedContentIds: Set<String> = emptySet(),
    val stopStreamInMenus: Boolean = false,
) {
    companion object {
        /**
         * Default country whitelist.  UK + GB both appear in real IPTV
         * playlists (some providers use the country code, others the
         * cultural name).  Empty out to accept all countries.
         */
        val DEFAULT_COUNTRY_WHITELIST: Set<String> = setOf("UK", "GB")

        /**
         * Default categories filtered out at sync time.  Reasonable
         * defaults for a family-friendly library; user can clear or
         * extend this via Settings.
         */
        val DEFAULT_EXCLUDED_CATEGORIES: Set<String> = setOf(
            "XXX", "Adult", "For Adults", "Adult 18+", "ADULTS",
            "Religious", "Religion",
        )

        /**
         * Default language whitelist.  English + unspecified — same as
         * the previously hard-coded `MovieEnrichmentService.WANTED_LANGUAGES`.
         */
        val DEFAULT_WANTED_LANGUAGES: Set<String> = setOf("en", "")

        /**
         * Countries we surface in the Settings + Wizard region picker.
         * Codes match the prefixes most IPTV providers use in M3U
         * `group-title` (e.g. `"UK | Entertainment"`).
         */
        val KNOWN_COUNTRIES: List<Pair<String, String>> = listOf(
            "UK" to "United Kingdom",
            "GB" to "Great Britain",
            "US" to "United States",
            "IE" to "Ireland",
            "CA" to "Canada",
            "AU" to "Australia",
            "NZ" to "New Zealand",
            "DE" to "Germany",
            "FR" to "France",
            "ES" to "Spain",
            "IT" to "Italy",
            "PT" to "Portugal",
            "NL" to "Netherlands",
            "BE" to "Belgium",
            "PL" to "Poland",
            "TR" to "Turkey",
            "RU" to "Russia",
            "BR" to "Brazil",
            "MX" to "Mexico",
            "AR" to "Argentina",
            "IN" to "India",
            "AE" to "UAE",
            "SA" to "Saudi Arabia",
            "EX-YU" to "Ex-Yugoslavia",
            "EXYU" to "Ex-Yugoslavia",
        )

        /**
         * Catalogue of TMDB languages we surface in the Settings filter
         * UI.  Codes are ISO-639-1, names are display strings.  This is
         * a curated subset of the ~200 TMDB languages — the long tail
         * isn't worth the UX cost.
         */
        val KNOWN_LANGUAGES: List<Pair<String, String>> = listOf(
            "en" to "English",
            "es" to "Spanish",
            "fr" to "French",
            "de" to "German",
            "it" to "Italian",
            "pt" to "Portuguese",
            "ru" to "Russian",
            "ar" to "Arabic",
            "hi" to "Hindi",
            "zh" to "Chinese",
            "ja" to "Japanese",
            "ko" to "Korean",
            "tr" to "Turkish",
            "pl" to "Polish",
            "nl" to "Dutch",
            "sv" to "Swedish",
            "no" to "Norwegian",
            "da" to "Danish",
            "fi" to "Finnish",
            "" to "Unknown",
        )
    }
}

/**
 * How often the background sync worker fires.  The semantics differ
 * from a strict interval: "Daily" means once per calendar day rather
 * than every 24h, so the user opening the app at 9pm one night and
 * 9am the next morning still triggers a sync.
 */
enum class SyncFrequency {
    /** Sync every launch. */
    EveryLaunch,
    /** Once per calendar day. */
    Daily,
    /** Once every two calendar days. */
    EveryTwoDays;

    fun shouldSync(lastSyncedEpochDay: Long, currentEpochDay: Long): Boolean = when (this) {
        EveryLaunch -> true
        Daily -> currentEpochDay > lastSyncedEpochDay
        EveryTwoDays -> currentEpochDay - lastSyncedEpochDay >= 2
    }
}
