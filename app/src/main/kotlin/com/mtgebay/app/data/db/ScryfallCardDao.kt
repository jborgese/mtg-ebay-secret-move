package com.mtgebay.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ScryfallCardDao {

    @Query("SELECT * FROM scryfall_card_cache WHERE scryfallId = :id LIMIT 1")
    suspend fun getById(id: String): ScryfallCardCache?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: ScryfallCardCache)

    /** Delete every cached entry whose fetch time is before [olderThanMillis]. */
    @Query("DELETE FROM scryfall_card_cache WHERE fetchedAt < :olderThanMillis")
    suspend fun deleteStale(olderThanMillis: Long): Int

    @Query("SELECT COUNT(*) FROM scryfall_card_cache")
    suspend fun count(): Int
}
