package com.fouu.habitflow.data.db.converter;

import androidx.room.TypeConverter;

import java.util.Date;

/**
 * Type converters for Room database.
 * Converts between Date <-> Long (timestamp) for SQLite storage.
 */
public class DateConverter {

    @TypeConverter
    public static Date fromTimestamp(Long value) {
        return value == null ? null : new Date(value);
    }

    @TypeConverter
    public static Long dateToTimestamp(Date date) {
        return date == null ? null : date.getTime();
    }
}
