package com.mtgebay.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Room database that aggregates every persisted table. v1 holds only the
 * Scryfall card cache; Phase 5 will add a `drafts` table for in-progress
 * eBay listings.
 */
@Database(
    entities = [ScryfallCardCache::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun scryfallCardDao(): ScryfallCardDao

    companion object {
        private const val DB_NAME = "mtg-ebay.db"

        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context).also { instance = it }
            }

        private fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, DB_NAME)
                .build()
    }
}
