package com.strata.tv.data.db

import androidx.room.TypeConverter
import java.time.Instant

/**
 * Room type converters for non-primitive column types.
 *
 * SQLite stores [Instant] as INTEGER milliseconds-since-epoch — the
 * same format drift used in v1, so a v1 SQLite file's `last_updated`
 * column is byte-compatible with v2's reader.
 */
class Converters {
    @TypeConverter
    fun fromInstant(value: Instant?): Long? = value?.toEpochMilli()

    @TypeConverter
    fun toInstant(value: Long?): Instant? = value?.let(Instant::ofEpochMilli)
}
