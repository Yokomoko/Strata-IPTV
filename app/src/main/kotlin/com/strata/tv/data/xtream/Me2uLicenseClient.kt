package com.strata.tv.data.xtream

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
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Talks to the Me2u Ultra Sky license server to discover the real
 * IPTV portal URL(s) for a given subscription.
 *
 * Me2u Ultra Sky (`com.dhmstreams.Me2uultrasky`) is a rebranded
 * XCIPTV v5.0.1 built on the nathnetwork BTV framework.  Like SkyGlass
 * it hides portal URLs behind a license server, but uses a different
 * server, different request parameters, and AES-128-CBC decryption
 * (rather than SkyGlass's XOR scheme).
 *
 * **Wire format** (reverse-engineered from the APK's JNI native-lib.so):
 *
 *  1. **Request**: GET `{server}/ApiIPTV.php?tag=licV3&l={md5(licenseKey)}
 *     &b={bundleId}&a={appVersion}`
 *  2. **Response**: Hex-encoded AES-128-CBC ciphertext.
 *     Decrypted with key=`DHMStreamssctkey`, iv=`DHMStreamsIDpara`
 *     to yield a JSON object with `success`, `app.portal`…`app.portal5`
 *     and matching `app.portal_name`…`app.portal5_name` fields.
 *
 * The license-key constant (`lkfj()`) is stored in libnative-lib.so;
 * the AES key/IV (`ekpfj()`/`ekivpfj()`) were also extracted from the
 * same library.  None of these are user-specific — customer identity
 * is established by the separate Xtream username + password probe.
 */
@Singleton
class Me2uLicenseClient @Inject constructor(
    private val http: OkHttpClient,
) {
    companion object {
        private const val TAG = "Me2uLicense"

        // Constants extracted from libnative-lib.so in com.dhmstreams.Me2uultrasky.
        private const val LICENSE_KEY = "DBC0E8EA-E845-4606-B07C-DHMREBRANDS1"
        private const val BUNDLE_ID   = "com.dhmstreams.Me2uultrasky"
        private const val APP_VERSION = "XCIPTV-v5.0.1"
        private const val LICENSE_SERVER =
            "http://dhmstreams.co.uk/DHMapps/Me2You/SkyGlass/api/"

        // AES-128-CBC key/IV from ekpfj()/ekivpfj() JNI functions in libnative-lib.so.
        private val AES_KEY = "DHMStreamssctkey".toByteArray(Charsets.UTF_8)
        private val AES_IV  = "DHMStreamsIDpara".toByteArray(Charsets.UTF_8)
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** A single backend portal returned by the Me2u license server. */
    data class Portal(
        val name: String,
        val url: String,
    )

    /**
     * Hit the license server, decrypt the response, return the list of
     * candidate portals.  Disabled portals (`"0"`) are filtered out.
     */
    suspend fun fetchPortals(): List<Portal> = withContext(Dispatchers.IO) {
        val l = md5Hex(LICENSE_KEY)
        val url = buildString {
            append(LICENSE_SERVER)
            append("ApiIPTV.php?tag=licV3")
            append("&l=").append(l)
            append("&b=").append(URLEncoder.encode(BUNDLE_ID, "UTF-8"))
            append("&a=").append(URLEncoder.encode(APP_VERSION, "UTF-8"))
        }

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Dalvik/2.1.0 (Linux; U; Android 9; AFTMM Build/PS7233)")
            .build()
        val hexBody = http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) error("Me2u license HTTP ${resp.code}")
            resp.body?.string().orEmpty().trim()
        }

        Log.d(TAG, "Received ${hexBody.length} hex chars from license server")

        val jsonText = decryptHex(hexBody)
        Log.d(TAG, "Decrypted preview: ${jsonText.take(200)}")

        val outer: Me2uLicenseResponse = json.decodeFromString(jsonText)
        if (outer.success != "1") {
            error("Me2u license rejected: success=${outer.success}")
        }

        val app = outer.app ?: error("No app block in Me2u license response")

        val candidates = listOf(
            Portal(app.portalName.orEmpty().ifBlank { "Ultra" },  app.portal.orEmpty()),
            Portal(app.portal2Name.orEmpty().ifBlank { "Ultra 1" }, app.portal2.orEmpty()),
            Portal(app.portal3Name.orEmpty().ifBlank { "Ultra 2" }, app.portal3.orEmpty()),
            Portal(app.portal4Name.orEmpty().ifBlank { "Ultra 3" }, app.portal4.orEmpty()),
            Portal(app.portal5Name.orEmpty().ifBlank { "Ultra 4" }, app.portal5.orEmpty()),
        ).filter { it.url.isNotBlank() && it.url != "0" && it.url.startsWith("http") }

        Log.i(TAG, "Fetched ${candidates.size} Me2u portals: ${candidates.map { it.name }}")
        candidates
    }

    /**
     * Try each portal against the supplied username + password.
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
            val request = Request.Builder()
                .url(authUrl)
                .removeHeader("User-Agent")
                .build()
            try {
                http.newCall(request).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    Log.i(
                        TAG,
                        "Probe '${portal.name}' (${portal.url}) HTTP ${resp.code} " +
                            "preview=${body.take(120)}",
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
    // Crypto helpers
    // ------------------------------------------------------------------

    /**
     * Decode a hex string (pairs of hex digits) to bytes, then
     * AES-128-CBC decrypt with the baked-in key and IV.
     */
    private fun decryptHex(hex: String): String {
        val paddedHex = if (hex.length % 2 != 0) hex + "0" else hex
        val bytes = ByteArray(paddedHex.length / 2) { i ->
            Integer.parseInt(paddedHex.substring(i * 2, i * 2 + 2), 16).toByte()
        }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(AES_KEY, "AES"),
            IvParameterSpec(AES_IV),
        )
        return String(cipher.doFinal(bytes), Charsets.UTF_8)
    }

    private fun md5Hex(text: String): String {
        val md = MessageDigest.getInstance("MD5")
        return md.digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

// =====================================================================
// DTOs
// =====================================================================

@Serializable
private data class Me2uLicenseResponse(
    val success: String? = null,
    val app: Me2uAppBlock? = null,
)

@Serializable
private data class Me2uAppBlock(
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
