package com.strata.tv.data.settings

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin client for Xtream Codes' `player_api.php` endpoint.
 *
 * We only use it for two things:
 *  1. **Connection test** during the first-run wizard / settings edit:
 *     hit the no-action variant and verify the JSON parses cleanly.
 *  2. **Subscription expiry**: same endpoint returns `exp_date` and
 *     `status` in the `user_info` payload — surfaced in Settings.
 *
 * Custom M3U providers don't have this endpoint; callers must check
 * [ProviderConfig.toPlayerApiUrl] for null first.
 */
@Singleton
class XtreamApi @Inject constructor(
    private val http: OkHttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    /**
     * Fetch the Xtream account info for a given provider config.
     *
     * Returns null if the provider has no Xtream API (e.g. raw M3U),
     * or if the request fails for any reason — never throws.
     */
    suspend fun accountInfo(config: ProviderConfig): AccountInfo? =
        withContext(Dispatchers.IO) {
            val url = config.toPlayerApiUrl() ?: return@withContext null
            try {
                val request = Request.Builder().url(url).build()
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext null
                    val body = response.body?.string() ?: return@withContext null
                    val wrapper = json.decodeFromString<XtreamResponse>(body)
                    AccountInfo.from(wrapper)
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Xtream accountInfo failed: ${e.message}")
                null
            }
        }

    /**
     * Cheap check that the provided credentials authenticate.  Used by
     * the first-run wizard's "Test connection" button.
     */
    suspend fun testConnection(config: ProviderConfig): Boolean =
        accountInfo(config)?.isAuthenticated == true

    data class AccountInfo(
        val username: String,
        val status: String,
        val isAuthenticated: Boolean,
        val expiresAt: Instant?,
        val activeConnections: Int,
        val maxConnections: Int,
    ) {
        companion object {
            internal fun from(resp: XtreamResponse): AccountInfo {
                val user = resp.userInfo
                val expiresAt = user?.expDate?.toLongOrNull()?.let { Instant.ofEpochSecond(it) }
                return AccountInfo(
                    username = user?.username.orEmpty(),
                    status = user?.status.orEmpty(),
                    isAuthenticated = (user?.auth ?: 0) == 1,
                    expiresAt = expiresAt,
                    activeConnections = user?.activeCons?.toIntOrNull() ?: 0,
                    maxConnections = user?.maxConnections?.toIntOrNull() ?: 0,
                )
            }
        }
    }

    @Serializable
    internal data class XtreamResponse(
        @SerialName("user_info") val userInfo: UserInfo? = null,
    )

    @Serializable
    internal data class UserInfo(
        val username: String? = null,
        val status: String? = null,
        val auth: Int = 0,
        @SerialName("exp_date") val expDate: String? = null,
        @SerialName("active_cons") val activeCons: String? = null,
        @SerialName("max_connections") val maxConnections: String? = null,
    )

    companion object {
        private const val TAG = "XtreamApi"
    }
}
