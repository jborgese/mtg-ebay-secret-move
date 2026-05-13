package com.mtgebay.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * App-wide Room database.
 *
 * v1: `scryfall_card_cache`
 * v2: + `drafts`
 *
 * Migrations between versions are intentionally absent in v1→v2. The app is
 * pre-release and we have no users to preserve data for; if the schema
 * changes we destructively rebuild the local DB. Proper migrations land
 * before public release (Phase 7 polish).
 */
@Database(
    entities = [ScryfallCardCache::class, Draft::class],
    version = 2,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun scryfallCardDao(): ScryfallCardDao
    abstract fun draftDao(): DraftDao

    companion object {
        private const val DB_NAME = "mtg-ebay.db"

        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context).also { instance = it }
            }

        @Suppress("DEPRECATION")
        private fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, DB_NAME)
                // Pre-release: nuke the local DB on schema change instead of writing migrations.
                .fallbackToDestructiveMigration()
                .build()
    }
}
