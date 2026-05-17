package com.strata.tv.testing

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest

/**
 * Stand-in for a real Xtream Codes IPTV provider, used by instrumented
 * tests so we never hit the live MyBunny.TV API.
 *
 * - `player_api.php` returns a configurable `AccountInfo` (defaults to
 *   an authenticated user with a far-future expiry).
 * - `get.php` returns the [m3uBody] verbatim.
 *
 * Lifecycle: `start()` before the test, `shutdown()` after.  Use
 * `baseUrl()` as the `host` field on [ProviderConfig].
 */
class FakeXtreamServer {
    private val server = MockWebServer()

    var authenticated: Boolean = true
    var m3uBody: String = ""
    var expiryEpochSeconds: Long = System.currentTimeMillis() / 1000 + 86_400 * 30

    fun start() {
        server.start()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/player_api.php") -> MockResponse()
                        .setResponseCode(200)
                        .setBody(playerApiBody())
                    path.startsWith("/get.php") -> MockResponse()
                        .setResponseCode(200)
                        .setBody(m3uBody)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
    }

    fun shutdown() {
        runCatching { server.shutdown() }
    }

    /** Host string with no trailing slash — drop straight into ProviderConfig.host. */
    fun host(): String = server.url("/").toString().trimEnd('/')

    private fun playerApiBody(): String {
        val auth = if (authenticated) 1 else 0
        return """
            {
              "user_info": {
                "username": "test",
                "auth": $auth,
                "status": "Active",
                "exp_date": "$expiryEpochSeconds",
                "active_cons": "1",
                "max_connections": "2"
              }
            }
        """.trimIndent()
    }
}
