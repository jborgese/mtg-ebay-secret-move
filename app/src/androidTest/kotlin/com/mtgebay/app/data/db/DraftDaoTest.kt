package com.mtgebay.app.data.db

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mtgebay.app.pricing.CardCondition
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DraftDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: DraftDao

    @Before fun setUp() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.draftDao()
    }

    @After fun tearDown() { db.close() }

    @Test fun upsertThenGetById_returnsSameRow() = runBlocking {
        val draft = sampleDraft(id = "d1", updatedAt = 100L)
        dao.upsert(draft)
        assertEquals(draft, dao.getById("d1"))
        assertEquals(1, dao.count())
    }

    @Test fun getById_returnsNullForMissing() = runBlocking {
        assertNull(dao.getById("nope"))
    }

    @Test fun upsert_replacesExistingByPrimaryKey() = runBlocking {
        dao.upsert(sampleDraft(id = "d1", retryCount = 0, updatedAt = 100L))
        dao.upsert(sampleDraft(id = "d1", retryCount = 3, updatedAt = 200L, lastError = "boom"))
        val row = dao.getById("d1")
        assertNotNull(row)
        assertEquals(3, row!!.retryCount)
        assertEquals(200L, row.updatedAt)
        assertEquals("boom", row.lastError)
        assertEquals(1, dao.count())
    }

    @Test fun observeAll_emitsSortedNewestFirst() = runBlocking {
        dao.upsert(sampleDraft(id = "old", updatedAt = 100L))
        dao.upsert(sampleDraft(id = "new", updatedAt = 300L))
        dao.upsert(sampleDraft(id = "mid", updatedAt = 200L))

        val snapshot = dao.observeAll().first()
        assertEquals(listOf("new", "mid", "old"), snapshot.map { it.id })
    }

    @Test fun deleteStale_removesOnlyOldRows() = runBlocking {
        dao.upsert(sampleDraft(id = "old", updatedAt = 100L))
        dao.upsert(sampleDraft(id = "new", updatedAt = 900L))

        val deleted = dao.deleteStale(olderThanMillis = 500L)
        assertEquals(1, deleted)
        assertEquals(1, dao.count())
        assertEquals("new", dao.getById("new")?.id)
    }

    @Test fun oldest_returnsAscendingByUpdatedAt() = runBlocking {
        dao.upsert(sampleDraft(id = "a", updatedAt = 300L))
        dao.upsert(sampleDraft(id = "b", updatedAt = 100L))
        dao.upsert(sampleDraft(id = "c", updatedAt = 200L))

        val oldest = dao.oldest(2).map { it.id }
        assertEquals(listOf("b", "c"), oldest)
    }

    @Test fun condition_roundTripsViaConverter() = runBlocking {
        dao.upsert(sampleDraft(id = "lp", condition = CardCondition.LP))
        assertEquals(CardCondition.LP, dao.getById("lp")?.condition)
    }

    private fun sampleDraft(
        id: String = UUID.randomUUID().toString(),
        updatedAt: Long = 0L,
        retryCount: Int = 0,
        lastError: String? = null,
        condition: CardCondition = CardCondition.NM,
    ) = Draft(
        id = id,
        createdAt = updatedAt,
        updatedAt = updatedAt,
        lastError = lastError,
        retryCount = retryCount,
        scryfallId = "abc-123",
        cardName = "Lightning Bolt",
        setName = "Test Set",
        rarity = "common",
        isFoil = false,
        condition = condition,
        startingBidCents = 100,
        packageWeightOz = 1.0f,
        title = "Lightning Bolt - Test Set",
    )
}
