package com.mtgebay.app.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface DraftDao {

    @Query("SELECT * FROM drafts WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): Draft?

    /** Drafts sorted newest-first; suitable for the drafts list screen. */
    @Query("SELECT * FROM drafts ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<Draft>>

    @Query("SELECT * FROM drafts ORDER BY updatedAt DESC")
    suspend fun listAll(): List<Draft>

    @Query("SELECT COUNT(*) FROM drafts")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(draft: Draft)

    @Update
    suspend fun update(draft: Draft)

    @Delete
    suspend fun delete(draft: Draft)

    @Query("DELETE FROM drafts WHERE id = :id")
    suspend fun deleteById(id: String)

    /**
     * Delete every draft last modified before [olderThanMillis]. Returns the
     * number of rows removed.
     */
    @Query("DELETE FROM drafts WHERE updatedAt < :olderThanMillis")
    suspend fun deleteStale(olderThanMillis: Long): Int

    /**
     * Return the [n] oldest drafts (by `updatedAt`). Used by [DraftRepository]
     * when applying the soft cap.
     */
    @Query("SELECT * FROM drafts ORDER BY updatedAt ASC LIMIT :n")
    suspend fun oldest(n: Int): List<Draft>
}
