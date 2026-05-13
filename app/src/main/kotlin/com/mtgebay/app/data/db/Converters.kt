package com.mtgebay.app.data.db

import androidx.room.TypeConverter
import com.mtgebay.app.pricing.CardCondition

/**
 * Room TypeConverters shared across the database. Each converter is a
 * stateless static-ish mapping; Room calls them automatically when a column
 * type doesn't have a built-in mapping.
 */
class Converters {

    @TypeConverter
    fun fromCondition(condition: CardCondition?): String? = condition?.name

    @TypeConverter
    fun toCondition(name: String?): CardCondition? =
        name?.let { runCatching { CardCondition.valueOf(it) }.getOrNull() }
}
