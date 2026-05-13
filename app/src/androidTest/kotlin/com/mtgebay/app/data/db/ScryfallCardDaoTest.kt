package com.mtgebay.app.data.db

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented test that exercises Room's generated DAO against an in-memory
 * SQLite database. Validates upsert + getById + deleteStale semantics.
 */
@RunWith(AndroidJUnit4::class)
class ScryfallCardDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: ScryfallCardDao

    @Before
    fun setUp() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.scryfallCardDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun upsertThenGetById_returnsTheStoredRow() = runBlocking {
        val row = ScryfallCardCache(scryfallId = "id-1", json = "{\"x\":1}", fetchedAt = 100L)
        dao.upsert(row)

        val fetched = dao.getById("id-1")
        assertEquals(row, fetched)
        assertEquals(1, dao.count())
    }

    @Test
    fun upsert_replacesExistingByPrimaryKey() = runBlocking {
        dao.upsert(ScryfallCardCache("id-1", "{\"v\":\"a\"}", fetchedAt = 100L))
        dao.upsert(ScryfallCardCache("id-1", "{\"v\":\"b\"}", fetchedAt = 200L))

        val fetched = dao.getById("id-1")
        assertEquals("{\"v\":\"b\"}", fetched?.json)
        assertEquals(200L, fetched?.fetchedAt)
        assertEquals(1, dao.count())
    }

    @Test
    fun getById_returnsNullForMissing() = runBlocking {
        assertNull(dao.getById("nope"))
    }

    @Test
    fun deleteStale_dropsRowsOlderThanCutoff() = runBlocking {
        dao.upsert(ScryfallCardCache("old", "{}", fetchedAt = 100L))
        dao.upsert(ScryfallCardCache("mid", "{}", fetchedAt = 500L))
        dao.upsert(ScryfallCardCache("new", "{}", fetchedAt = 900L))

        val deleted = dao.deleteStale(olderThanMillis = 600L)
        assertEquals(2, deleted)
        assertEquals(1, dao.count())
        assertEquals("new", dao.getById("new")?.scryfallId)
    }
}
