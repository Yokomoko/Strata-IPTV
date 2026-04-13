package com.strata.tv.data.repo

import android.util.Log
import com.strata.tv.AppConfig
import com.strata.tv.data.db.ProgrammeDao
import com.strata.tv.data.xmltv.XmltvParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fetches the XMLTV EPG feed from [AppConfig.EPG_URL] and writes
 * programmes into Room via [XmltvParser].
 *
 * Freshness gate: if [ProgrammeDao.hasAfter] reports data beyond
 * `now + 2 hours`, the fetch is skipped entirely.  This mirrors v1's
 * "skip if data is fresh" check (parity with
 * `lib/services/epg_fetch_service.dart`).
 *
 * The full HTTP + parse pipeline runs on [Dispatchers.IO] and is
 * cancellation-safe — the caller can cancel the coroutine at any point
 * and the parser will abort at the next batch boundary.
 *
 * Future phases will move this to WorkManager for periodic refresh;
 * for Phase 5 the ViewModel fires it once on init.
 */
@Singleton
class EpgFetchService @Inject constructor(
    private val http: OkHttpClient,
    private val parser: XmltvParser,
    private val programmeDao: ProgrammeDao,
) {

    companion object {
        private const val TAG = "EpgFetchService"
    }

    /**
     * Fetch + parse the EPG, unless data is still fresh.
     *
     * @return the number of programmes stored, or 0 if skipped.
     */
    suspend fun fetchIfNeeded(): Int {
        val cutoff = Instant.now().plus(2, ChronoUnit.HOURS)
        if (programmeDao.hasAfter(cutoff)) {
            Log.d(TAG, "EPG data is fresh (has programmes after $cutoff), skipping fetch")
            return 0
        }

        Log.d(TAG, "EPG data is stale, fetching from ${AppConfig.EPG_URL}")

        // Purge old programmes before fetching new ones — keeps the
        // table from growing unboundedly across refreshes.
        val yesterday = Instant.now().minus(24, ChronoUnit.HOURS)
        programmeDao.purgeBefore(yesterday)

        return withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(AppConfig.EPG_URL)
                .build()

            val response = http.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e(TAG, "HTTP ${response.code} fetching EPG")
                error("HTTP ${response.code} fetching ${AppConfig.EPG_URL}")
            }

            val body = response.body ?: error("Empty EPG response body")

            // Stream the response body directly into the parser —
            // this avoids buffering the entire 156 MB into a String.
            body.byteStream().use { stream ->
                parser.parseAndStore(stream)
            }
        }
    }
}
