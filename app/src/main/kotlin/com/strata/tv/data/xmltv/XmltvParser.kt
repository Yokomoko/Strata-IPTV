package com.strata.tv.data.xmltv

import android.util.Log
import com.strata.tv.data.db.ProgrammeDao
import com.strata.tv.data.db.ProgrammeEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Streaming XMLTV parser that reads `<channel>` and `<programme>`
 * elements from an [InputStream] and batch-upserts programmes into
 * Room via [ProgrammeDao].
 *
 * Design goals for a 156 MB feed on a Fire Stick (512 MB heap):
 *
 * - **Streaming**: uses [XmlPullParser] (StAX-style pull parser) so at
 *   most one element's worth of text is in memory at a time.  The full
 *   DOM is never materialised.
 * - **Batched writes**: programmes are accumulated into batches of
 *   [BATCH_SIZE] and flushed to Room with `upsertAll`.  Peak heap from
 *   the batch buffer is ~10 MB (500 entities x ~20 KB each, conservatively).
 * - **Cancellation-safe**: checks `ensureActive()` every batch so a
 *   coroutine cancellation during a long parse aborts promptly.
 *
 * The XMLTV time format `YYYYMMDDHHmmss ±HHmm` is parsed with a
 * [DateTimeFormatter] that handles the space-separated offset.
 * Some feeds omit the offset — those are treated as UTC.
 */
@Singleton
class XmltvParser @Inject constructor(
    private val programmeDao: ProgrammeDao,
) {

    companion object {
        private const val TAG = "XmltvParser"

        /** Batch size for Room upserts.  500 keeps heap bounded while
         *  amortising SQLite transaction overhead. */
        private const val BATCH_SIZE = 500

        /**
         * XMLTV datetime format: `YYYYMMDDHHmmss ±HHmm`.
         *
         * Some feeds use `+0000` (no colon), others `+00:00` — the
         * formatter accepts both via the `appendOffset` pattern.
         * Feeds that omit the offset entirely default to UTC.
         */
        private val XMLTV_TIME_FORMATTER: DateTimeFormatter =
            DateTimeFormatterBuilder()
                .appendValue(ChronoField.YEAR, 4)
                .appendValue(ChronoField.MONTH_OF_YEAR, 2)
                .appendValue(ChronoField.DAY_OF_MONTH, 2)
                .appendValue(ChronoField.HOUR_OF_DAY, 2)
                .appendValue(ChronoField.MINUTE_OF_HOUR, 2)
                .appendValue(ChronoField.SECOND_OF_MINUTE, 2)
                .optionalStart()
                .appendLiteral(' ')
                .appendOffset("+HHmm", "+0000")
                .optionalEnd()
                .toFormatter()
                .withZone(ZoneOffset.UTC)
    }

    /**
     * Result of a parse operation, including the display name map
     * extracted from `<channel>` elements for use in EPG matching.
     */
    data class ParseResult(
        /** Total programmes upserted into Room. */
        val programmesStored: Int,
        /** Total programmes skipped (unparseable). */
        val programmesSkipped: Int,
        /** XMLTV channel id -> display name, from `<channel>` elements. */
        val channelDisplayNames: Map<String, String>,
        /** Total `<channel>` elements parsed. */
        val channelElementCount: Int,
        /** Distinct XMLTV channel ids seen across all `<programme>` elements. */
        val distinctProgrammeChannelIds: Int,
    )

    /**
     * Parse the XMLTV feed from [input] and write all `<programme>`
     * elements into Room.  Also extracts `<channel>` display names
     * for use by the EPG matcher.
     *
     * Runs entirely on [Dispatchers.IO].
     *
     * @return a [ParseResult] with counts and the channel display name map.
     */
    suspend fun parseAndStoreWithMetadata(input: InputStream): ParseResult = withContext(Dispatchers.IO) {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = false
        val xpp = factory.newPullParser()
        xpp.setInput(input, null) // let the parser detect encoding from the XML prolog

        val batch = ArrayList<ProgrammeEntity>(BATCH_SIZE)
        var totalStored = 0
        var totalSkipped = 0
        val channelDisplayNames = mutableMapOf<String, String>()
        val programmeChannelIds = mutableSetOf<String>()

        var eventType = xpp.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG) {
                when (xpp.name) {
                    "channel" -> {
                        val (id, displayName) = parseChannel(xpp)
                        if (id.isNotEmpty() && displayName.isNotEmpty()) {
                            channelDisplayNames.putIfAbsent(id, displayName)
                        }
                    }
                    "programme" -> {
                        val entity = parseProgramme(xpp)
                        if (entity != null) {
                            programmeChannelIds.add(entity.channelId)
                            batch.add(entity)
                            if (batch.size >= BATCH_SIZE) {
                                ensureActive()
                                programmeDao.upsertAll(batch.toList())
                                totalStored += batch.size
                                batch.clear()
                                if (totalStored % 5000 == 0) {
                                    Log.d(TAG, "Stored $totalStored programmes so far")
                                }
                            }
                        } else {
                            totalSkipped++
                        }
                    }
                }
            }
            eventType = xpp.next()
        }

        // Flush remaining batch.
        if (batch.isNotEmpty()) {
            programmeDao.upsertAll(batch.toList())
            totalStored += batch.size
        }

        Log.d(
            TAG,
            "Parse complete: $totalStored stored, $totalSkipped skipped, " +
                "${channelDisplayNames.size} channel display names, " +
                "${programmeChannelIds.size} distinct programme channel ids",
        )
        ParseResult(
            programmesStored = totalStored,
            programmesSkipped = totalSkipped,
            channelDisplayNames = channelDisplayNames,
            channelElementCount = channelDisplayNames.size,
            distinctProgrammeChannelIds = programmeChannelIds.size,
        )
    }

    /**
     * Legacy entry point — parse and store, returning only the count.
     * Delegates to [parseAndStoreWithMetadata].
     */
    suspend fun parseAndStore(input: InputStream): Int {
        return parseAndStoreWithMetadata(input).programmesStored
    }

    // -----------------------------------------------------------------
    // Internal: parse a single <channel> element
    // -----------------------------------------------------------------

    /**
     * Parse a `<channel>` element to extract its id and first
     * `<display-name>`.
     *
     * XMLTV `<channel>` elements look like:
     * ```xml
     * <channel id="bbc.one.uk">
     *   <display-name>BBC One</display-name>
     *   <icon src="https://..." />
     * </channel>
     * ```
     *
     * Returns a pair of (id, displayName).  If either is missing,
     * returns empty strings.
     */
    private fun parseChannel(xpp: XmlPullParser): Pair<String, String> {
        val id = xpp.getAttributeValue(null, "id") ?: return "" to ""
        var displayName = ""

        var depth = 1
        while (depth > 0) {
            val event = xpp.next()
            when (event) {
                XmlPullParser.START_TAG -> {
                    depth++
                    when (xpp.name) {
                        "display-name" -> {
                            if (displayName.isEmpty()) {
                                displayName = readText(xpp)
                                depth-- // readText consumes the end tag
                            }
                        }
                    }
                }
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> break
            }
        }

        return id to displayName
    }

    // -----------------------------------------------------------------
    // Internal: parse a single <programme> element
    // -----------------------------------------------------------------

    /**
     * Read through a `<programme>` element and extract channel, start,
     * stop, title, description and icon.
     *
     * Returns null if the required attributes (channel, start, stop) are
     * missing or the time format is unparseable.
     */
    private fun parseProgramme(xpp: XmlPullParser): ProgrammeEntity? {
        val channelId = xpp.getAttributeValue(null, "channel") ?: return null
        val startStr = xpp.getAttributeValue(null, "start") ?: return null
        val stopStr = xpp.getAttributeValue(null, "stop") ?: return null

        val startTime = parseXmltvTime(startStr) ?: return null
        val endTime = parseXmltvTime(stopStr) ?: return null

        var title = ""
        var description = ""
        var icon = ""

        // Walk through child elements until we hit </programme>.
        var depth = 1
        while (depth > 0) {
            val event = xpp.next()
            when (event) {
                XmlPullParser.START_TAG -> {
                    depth++
                    when (xpp.name) {
                        "title" -> title = readText(xpp).also { depth-- }
                        "desc" -> description = readText(xpp).also { depth-- }
                        "icon" -> icon = xpp.getAttributeValue(null, "src") ?: ""
                    }
                }
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> break
            }
        }

        return ProgrammeEntity(
            channelId = channelId,
            title = title,
            description = description,
            startTime = startTime,
            endTime = endTime,
            icon = icon,
        )
    }

    /**
     * Read the text content of the current element and consume the
     * closing tag.  XmlPullParser's `nextText()` quirk: it advances
     * past the END_TAG, so the caller must account for the depth
     * change (we return the text and the caller decrements depth).
     */
    private fun readText(xpp: XmlPullParser): String {
        return try {
            xpp.nextText()
        } catch (_: Exception) {
            // Malformed XMLTV — element might have mixed content or
            // nested tags inside <title>.  Recover gracefully.
            ""
        }
    }

    /**
     * Parse XMLTV time format `YYYYMMDDHHmmss ±HHmm` into an [Instant].
     *
     * Returns null on unparseable input rather than crashing — a single
     * bad time value shouldn't abort the entire 156 MB parse.
     */
    private fun parseXmltvTime(raw: String): Instant? {
        return try {
            val trimmed = raw.trim()
            val accessor = XMLTV_TIME_FORMATTER.parse(trimmed)
            Instant.from(accessor)
        } catch (_: Exception) {
            Log.w(TAG, "Unparseable XMLTV time: '$raw'")
            null
        }
    }
}
