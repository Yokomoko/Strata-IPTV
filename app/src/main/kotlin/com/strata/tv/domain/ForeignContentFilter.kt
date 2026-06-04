package com.strata.tv.domain

/**
 * Maps a content category / group title to the country it clearly
 * belongs to, when the category name itself names a foreign language or
 * country (e.g. "German", "Hindi Movies", "TH | Thai", "Arabic Series").
 *
 * Xtream providers (like mybunny.tv) name their series/VOD categories
 * after a language — "German", "Finnish", "Hindi", "Thai", "French" —
 * with NO `"UK |"`-style country prefix.  The sync-time country
 * whitelist only looked at a `|`-delimited prefix, so these slipped
 * straight through and foreign-language shows appeared even with GB/UK
 * selected.  This recovers the country from the category name so the
 * whitelist can drop them.
 *
 * Returns `null` for neutral category names ("Drama", "Entertainment",
 * "Comedy") so genuine English-speaking content is never dropped — we
 * only exclude a category when it positively identifies as a country
 * the user didn't ask for.
 */
object ForeignContentFilter {

    // Lowercase token (matched as a whole word / substring on the
    // category title) → ISO country code the whitelist compares against.
    private val tokenToCountry: Map<String, String> = mapOf(
        "german" to "DE", "deutsch" to "DE", "germany" to "DE",
        "finnish" to "FI", "suomi" to "FI", "finland" to "FI",
        "french" to "FR", "francais" to "FR", "français" to "FR", "france" to "FR",
        "hindi" to "IN", "punjabi" to "IN", "tamil" to "IN", "telugu" to "IN",
        "bengali" to "IN", "marathi" to "IN", "kannada" to "IN", "malayalam" to "IN",
        "india" to "IN", "indian" to "IN", "bollywood" to "IN", "desi" to "IN",
        "urdu" to "PK", "pakistan" to "PK", "pakistani" to "PK",
        "thai" to "TH", "thailand" to "TH",
        "arabic" to "AR", "arab" to "AR",
        "turkish" to "TR", "turkce" to "TR", "türkçe" to "TR", "turkey" to "TR",
        "polish" to "PL", "polski" to "PL", "poland" to "PL",
        "spanish" to "ES", "espanol" to "ES", "español" to "ES", "spain" to "ES",
        "latino" to "ES", "latin" to "ES",
        "italian" to "IT", "italiano" to "IT", "italia" to "IT", "italy" to "IT",
        "portuguese" to "PT", "portugues" to "PT", "português" to "PT",
        "brasil" to "BR", "brazil" to "BR", "brazilian" to "BR",
        "dutch" to "NL", "nederland" to "NL", "holland" to "NL",
        "swedish" to "SE", "svenska" to "SE", "sweden" to "SE",
        "norwegian" to "NO", "norsk" to "NO", "norway" to "NO",
        "danish" to "DK", "dansk" to "DK", "denmark" to "DK",
        "russian" to "RU", "russia" to "RU",
        "romanian" to "RO", "romania" to "RO",
        "greek" to "GR", "greece" to "GR",
        "chinese" to "CN", "mandarin" to "CN", "cantonese" to "CN", "china" to "CN",
        "korean" to "KR", "korea" to "KR",
        "japanese" to "JP", "japan" to "JP",
        "vietnamese" to "VN", "vietnam" to "VN",
        "albanian" to "AL", "shqip" to "AL", "albania" to "AL",
        "serbian" to "RS", "croatian" to "RS", "bosnian" to "RS",
        "ex yu" to "RS", "exyu" to "RS", "balkan" to "RS",
        "persian" to "IR", "farsi" to "IR", "iran" to "IR",
        "hebrew" to "IL", "israel" to "IL",
        "kurdish" to "IQ",
        "somali" to "SO",
        "afghan" to "AF", "pashto" to "AF",
        "filipino" to "PH", "tagalog" to "PH",
        "hungarian" to "HU", "hungary" to "HU",
        "czech" to "CZ",
        "bulgarian" to "BG", "bulgaria" to "BG",
        // Short ISO-ish codes that providers use as bare category names
        // or "RO | …" prefixes.  Only codes that AREN'T common English
        // words are listed (so no "in"/"it"/"no"/"is" false positives);
        // the word-boundary check in countryFor keeps them from matching
        // inside longer words.
        "ro" to "RO", "sr" to "RS", "rs" to "RS", "tr" to "TR",
        "fr" to "FR", "de" to "DE", "es" to "ES", "pt" to "PT",
        "pl" to "PL", "cz" to "CZ", "hu" to "HU", "gr" to "GR",
        "fi" to "FI", "ru" to "RU", "ir" to "IR", "nl" to "NL",
        "se" to "SE", "dk" to "DK", "th" to "TH", "cn" to "CN",
        "jp" to "JP", "kr" to "KR", "vn" to "VN", "il" to "IL",
        "pk" to "PK", "af" to "AF", "ph" to "PH", "al" to "AL",
    )

    /**
     * Country code this category positively identifies as, or `null`
     * if the name is country-neutral (so it should be kept).
     */
    fun countryFor(categoryTitle: String): String? {
        if (categoryTitle.isBlank()) return null
        val lower = categoryTitle.lowercase()
        for ((token, country) in tokenToCountry) {
            // Word-ish match: token surrounded by non-letters (or string
            // ends) so "german" matches "German Movies" / "VOD German"
            // but a token isn't matched inside an unrelated longer word.
            val idx = lower.indexOf(token)
            if (idx >= 0) {
                val before = if (idx == 0) ' ' else lower[idx - 1]
                val afterIdx = idx + token.length
                val after = if (afterIdx >= lower.length) ' ' else lower[afterIdx]
                if (!before.isLetter() && !after.isLetter()) return country
            }
        }
        return null
    }

    /**
     * True if [categoryTitle] should be dropped given the user's
     * [countryWhitelist] (uppercase ISO codes).  Only drops when the
     * category positively names a country NOT in the whitelist; neutral
     * categories are always kept.  No-op when the whitelist is empty.
     */
    fun shouldExclude(categoryTitle: String, countryWhitelist: Set<String>): Boolean {
        if (countryWhitelist.isEmpty()) return false
        val country = countryFor(categoryTitle) ?: return false
        return country !in countryWhitelist
    }
}
