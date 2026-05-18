package com.strata.tv.data.settings

/**
 * The IPTV provider the user has configured.  Either a built-in
 * Xtream Codes provider (MyBunny.TV, GTV, etc.) where we have the
 * host URL baked in and the user supplies credentials, or a fully
 * custom entry where the user supplies everything.
 *
 * The settings layer stores this; [ProviderConfig.toM3uUrl] /
 * [ProviderConfig.toPlayerApiUrl] resolve the runtime URLs.
 */
data class ProviderConfig(
    /** Built-in or "custom".  Empty/null means "not configured yet". */
    val providerId: String = "",
    /** Xtream host (no trailing slash) e.g. "http://mybunny.tv". */
    val host: String = "",
    val username: String = "",
    val password: String = "",
    /**
     * Used when [providerId] = "custom_m3u": a raw M3U URL with
     * credentials baked in.  [host]/[username]/[password] are unused
     * in this case.
     */
    val customM3uUrl: String = "",
    /**
     * When true, pull the user's website-curated filtered playlist
     * from `{host}/client/download.php?u=…&p=…` instead of the full
     * provider catalogue.  This is the "Personal Playlist" the user
     * can build at https://mybunny.tv (and other Xtream UI-based
     * providers); it returns a standard M3U so the regular parser
     * pipeline handles it without the JSON fallback.
     *
     * Defaults to `false` — the wizard surfaces this as an opt-in
     * with a warning that the user must have actually curated a list
     * on the provider's website first, otherwise the playlist will
     * come back empty.
     */
    val useFilteredPlaylist: Boolean = false,
) {
    val isConfigured: Boolean
        get() = when (providerId) {
            "" -> false
            "custom_m3u" -> customM3uUrl.isNotBlank()
            else -> host.isNotBlank() && username.isNotBlank() && password.isNotBlank()
        }

    /** Resolve the M3U playlist URL for [SyncService.syncFromUrl]. */
    fun toM3uUrl(): String = when {
        providerId == "custom_m3u" -> customM3uUrl
        useFilteredPlaylist -> {
            // Website-curated personal playlist.  Returns a real M3U so
            // the standard parser handles it; no JSON fallback needed.
            val base = host.trimEnd('/')
            "$base/client/download.php?u=$username&p=$password"
        }
        else -> {
            // Don't pin output=ts.  Some providers (MyBunny.TV is one)
            // return an empty body when this parameter is set.  The
            // standard playlist URL is just type=m3u_plus.
            val base = host.trimEnd('/')
            "$base/get.php?username=$username&password=$password&type=m3u_plus"
        }
    }

    /**
     * Resolve the XMLTV EPG URL for this provider.
     *
     * - **MyBunny.TV** publishes a public EPG at `/epg.xml`, no auth.
     * - **Other Xtream Codes** providers typically host the EPG at the
     *   standard `/xmltv.php` endpoint behind the user's credentials.
     * - **Raw M3U** providers don't expose an EPG; returns null.
     */
    fun toEpgUrl(): String? = when (providerId) {
        "custom_m3u" -> null
        "mybunny_tv" -> "https://mybunny.tv/epg.xml"
        else -> {
            val base = host.trimEnd('/')
            "$base/xmltv.php?username=$username&password=$password"
        }
    }

    /** Resolve the Xtream `player_api.php` URL for account info / subscription. */
    fun toPlayerApiUrl(action: String? = null): String? {
        if (providerId == "custom_m3u") return null
        val base = host.trimEnd('/')
        val core = "$base/player_api.php?username=$username&password=$password"
        return if (action != null) "$core&action=$action" else core
    }
}

/**
 * Catalogue of built-in providers shown in the first-run wizard
 * and Settings provider picker.  Hosts are public knowledge —
 * credentials are entered by the user at runtime, never embedded.
 */
object BuiltInProviders {
    data class Entry(
        val id: String,
        val displayName: String,
        val host: String,
        val description: String,
    )

    val ALL: List<Entry> = listOf(
        Entry(
            id = "mybunny_tv",
            displayName = "MyBunny.TV",
            host = "https://mybunny.tv",
            description = "Xtream Codes. Enter your MyBunny.TV username and password.",
        ),
        Entry(
            id = "skyglass",
            displayName = "SkyGlass",
            host = "http://skyglass.vip:8080",
            description = "Xtream Codes. The same username and password you use " +
                "in the SkyGlass wrapper app.",
        ),
        Entry(
            id = "custom_xtream",
            displayName = "Custom Xtream",
            host = "",
            description = "For B1G, GTV, Ultimate, and other Xtream providers. " +
                "Enter host URL, username and password.",
        ),
        Entry(
            id = "custom_m3u",
            displayName = "Custom M3U URL",
            host = "",
            description = "Paste a raw M3U URL that already has credentials baked in.",
        ),
    )

    fun byId(id: String): Entry? = ALL.firstOrNull { it.id == id }
}
