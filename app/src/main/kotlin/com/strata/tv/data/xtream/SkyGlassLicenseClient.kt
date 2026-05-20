package com.strata.tv.data.xtream

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Talks to the SkyGlass app's license server to discover the real
 * IPTV portal URL(s) for a given subscription.
 *
 * SkyGlass (`com.equiptv.xciptvskyglas`) is a rebranded XCIPTV v911
 * that doesn't ship any portal URL in its APK — it fetches a list
 * of 4–5 backend providers (B1G, B1G 2, Ultimate, GTV, …) at startup
 * from a license server, then routes the user's credentials to
 * whichever one their plan is subscribed to.
 *
 * We mirror that handshake here so the SkyGlass preset in our wizard
 * doesn't have to hard-code a guess — Strata fetches the same list
 * and tries each portal against the user's username + password until
 * one accepts.
 *
 * **Wire format** (reverse-engineered from `SplashActivity.m()` in
 * the decompiled APK):
 *
 *  1. **Request**: GET `{base}/ApiIPTV.php?tag=licV4&l={md5(licenseKey)}
 *     &an={appName}&el={b64(xor(licenseKey, mixedKey))}&ea={...}&eb={...}`
 *     where `mixedKey = md5(licenseKey) + appName` and the XOR is
 *     plain byte-by-byte XOR with key-byte repetition.
 *  2. **Response**: JSON with `cid`, `app`, `portal`, `urls`, `button`,
 *     `settings`.  Each opaque field is base64(xor(plainJson, vKey))
 *     where `vKey = appName + cid + fieldSuffix`.  The `portal` field
 *     decrypts to a JSON object with `portal`, `portal2`…`portal5`
 *     (URLs, "0" means disabled) and matching `portal_name` labels.
 *
 * The license-key constant, app name and package id are baked into
 * the SkyGlass APK — they're not user-specific.  Customer identity
 * is established later via the actual Xtream username + password on
 * one of the returned portals.
 */
@Singleton
class SkyGlassLicenseClient @Inject constructor(
    private val http: OkHttpClient,
) {
    companion object {
        private const val TAG = "SkyGlassLicense"

        // Constants baked into com.equiptv.xciptvskyglas (the SkyGlass APK).
        // See `Config.f10912a` / `Config.BUNDLE_ID` / `PanelUrl._panelUrl`.
        private const val LICENSE_KEY = "213E5442-3E12-213E-QUIP-1213A1EQUIP"
        private const val APP_NAME = "SkyGlass"
        private const val PACKAGE_NAME = "com.equiptv.xciptvskyglas"
        private const val LICENSE_BASE_URL =
            "https://ph.blaststream.co.uk/xc911/tvglassthore/api/ottrun/"

        // Mirror what the SkyGlass app sends — Volley/HurlStack with
        // Android's default UA.  Spoofing it stops the license server
        // from rejecting us as a non-XCIPTV client.
        private const val USER_AGENT = "okhttp/4.12.0"
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** A single backend portal returned by the SkyGlass license server. */
    data class Portal(
        /** Display name shown in the SkyGlass UI (B1G, GTV, etc.). */
        val name: String,
        /** Xtream Codes host URL — pass straight to ProviderConfig.host. */
        val url: String,
    )

    /**
     * Hit the license server, decrypt the response, return the list of
     * candidate portals.  Disabled portals (`"0"`) are filtered out.
     */
    suspend fun fetchPortals(): List<Portal> = withContext(Dispatchers.IO) {
        val licMd5 = md5Hex(LICENSE_KEY)
        val mixedKey = licMd5 + APP_NAME

        val el = base64NoWrap(xor(LICENSE_KEY.toByteArray(), mixedKey.toByteArray()))
        val ea = base64NoWrap(xor(APP_NAME.toByteArray(), mixedKey.toByteArray()))
        val eb = base64NoWrap(xor(PACKAGE_NAME.toByteArray(), mixedKey.toByteArray()))

        val url = buildString {
            append(LICENSE_BASE_URL)
            append("ApiIPTV.php?tag=licV4")
            append("&l=").append(licMd5)
            append("&an=").append(URLEncoder.encode(APP_NAME, "UTF-8"))
            append("&el=").append(URLEncoder.encode(el, "UTF-8"))
            append("&ea=").append(URLEncoder.encode(ea, "UTF-8"))
            append("&eb=").append(URLEncoder.encode(eb, "UTF-8"))
        }

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .build()
        val body = http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                error("License HTTP ${resp.code}")
            }
            resp.body?.string().orEmpty()
        }

        val outer: LicenseResponse = json.decodeFromString(body)
        if (outer.success != "1") {
            error("License rejected: success=${outer.success}, status=${outer.status}")
        }

        // Per-field XOR key: appName + cid + fieldSuffix (spaces stripped).
        val keyPrefix = (APP_NAME + outer.cid).replace(" ", "")
        val portalJson = decryptField(outer.portal, keyPrefix + "portal")
        Log.d(TAG, "Portal JSON: $portalJson")

        val portals: PortalsResponse = json.decodeFromString(portalJson)
        val candidates = listOf(
            Portal(portals.portalName.orEmpty().ifBlank { "Portal 1" }, portals.portal.orEmpty()),
            Portal(portals.portal2Name.orEmpty().ifBlank { "Portal 2" }, portals.portal2.orEmpty()),
            Portal(portals.portal3Name.orEmpty().ifBlank { "Portal 3" }, portals.portal3.orEmpty()),
            Portal(portals.portal4Name.orEmpty().ifBlank { "Portal 4" }, portals.portal4.orEmpty()),
            Portal(portals.portal5Name.orEmpty().ifBlank { "Portal 5" }, portals.portal5.orEmpty()),
        ).filter { it.url.isNotBlank() && it.url != "0" && it.url.startsWith("http") }

        Log.i(TAG, "Fetched ${candidates.size} SkyGlass portals: ${candidates.map { it.name }}")
        candidates
    }

    /**
     * Try each candidate portal against the supplied username + password.
     * Returns the first portal whose `player_api.php` accepts the
     * credentials (`auth = 1`), or null if all reject.
     */
    suspend fun probePortals(
        portals: List<Portal>,
        username: String,
        password: String,
    ): Portal? = withContext(Dispatchers.IO) {
        for (portal in portals) {
            val host = portal.url.trimEnd('/')
            val authUrl = "$host/player_api.php?username=$username&password=$password"
            // Send the SAME headers the SkyGlass app's Volley/HurlStack
            // stack sends — an empty User-Agent (Android's default
            // HttpURLConnection injects "Java/<version>") + no extras.
            // OkHttp's default `okhttp/4.x` UA was tripping the portals'
            // anti-bot filter and getting 512/513 back.
            val request = Request.Builder()
                .url(authUrl)
                .removeHeader("User-Agent")  // let OkHttp not set one
                .build()
            try {
                http.newCall(request).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    Log.i(
                        TAG,
                        "Probe '${portal.name}' (${portal.url}) HTTP ${resp.code} " +
                            "body=${body.length}B preview=${body.take(120)}",
                    )
                    if (!resp.isSuccessful) return@use
                    if (body.contains("\"auth\":1") || body.contains("\"auth\": 1")) {
                        Log.i(TAG, "Portal '${portal.name}' accepted creds")
                        return@withContext portal
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Probe '${portal.name}' failed: ${t.message}")
            }
        }
        null
    }

    // ------------------------------------------------------------------
    // Crypto + encoding helpers
    // ------------------------------------------------------------------

    /** Base64-decode then XOR with the per-field key. */
    private fun decryptField(b64: String, key: String): String {
        val padded = b64 + "=".repeat((4 - b64.length % 4) % 4)
        val raw = Base64.decode(padded, Base64.DEFAULT)
        val plain = xor(raw, key.toByteArray())
        return String(plain, Charsets.UTF_8)
    }

    private fun md5Hex(text: String): String {
        val md = MessageDigest.getInstance("MD5")
        val bytes = md.digest(text.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun xor(data: ByteArray, key: ByteArray): ByteArray {
        val out = ByteArray(data.size)
        for (i in data.indices) {
            out[i] = (data[i].toInt() xor key[i % key.size].toInt()).toByte()
        }
        return out
    }

    private fun base64NoWrap(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.NO_WRAP)
}

// =====================================================================
// DTOs
// =====================================================================

@Serializable
private data class LicenseResponse(
    val success: String? = null,
    val status: String? = null,
    val cid: String = "",
    val app: String = "",
    val portal: String = "",
    val urls: String = "",
)

@Serializable
private data class PortalsResponse(
    @SerialName("panel") val panel: String? = null,
    val portal: String? = null,
    @SerialName("portal_name") val portalName: String? = null,
    val portal2: String? = null,
    @SerialName("portal2_name") val portal2Name: String? = null,
    val portal3: String? = null,
    @SerialName("portal3_name") val portal3Name: String? = null,
    val portal4: String? = null,
    @SerialName("portal4_name") val portal4Name: String? = null,
    val portal5: String? = null,
    @SerialName("portal5_name") val portal5Name: String? = null,
)
