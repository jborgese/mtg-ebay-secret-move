package com.mtgebay.app.data

import com.mtgebay.app.data.db.Draft
import com.mtgebay.app.data.db.DraftDao
import com.mtgebay.app.pricing.CardCondition
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File

class DraftRepositoryTest {

    @get:Rule val tempFolder = TemporaryFolder()

    private val dao: DraftDao = mockk(relaxed = true)
    private var now: Long = 1_000_000L
    private val softCap = 3

    private fun repo() = DraftRepository(
        dao = dao,
        photosDir = tempFolder.root,
        now = { now },
        softCap = softCap,
    )

    @Test
    fun create_underCap_doesNotEvictAndStampsTimestamps() = runBlocking {
        coEvery { dao.count() } returns 1
        coEvery { dao.upsert(any()) } just Runs

        val result = repo().create(
            scryfallId = "abc",
            cardName = "Test",
            setName = "Set",
            rarity = null,
            isFoil = false,
            condition = CardCondition.NM,
            startingBidCents = 500L,
            packageWeightOz = 1.0f,
            title = "Test - Set",
        )

        assertEquals(0, result.evictedCount)
        assertEquals(now, result.draft.createdAt)
        assertEquals(now, result.draft.updatedAt)
        assertEquals("Test", result.draft.cardName)
        coVerify(exactly = 1) { dao.upsert(any()) }
        coVerify(exactly = 0) { dao.oldest(any()) }
    }

    @Test
    fun create_atCap_evictsOldestAndDeletesItsPhotos() = runBlocking {
        // Cap is 3 → at count=3 we should evict 1 to make room for the new draft.
        coEvery { dao.count() } returns 3
        val victim = sampleDraft(id = "victim", updatedAt = 1L)
        coEvery { dao.oldest(1) } returns listOf(victim)
        coEvery { dao.delete(victim) } just Runs
        coEvery { dao.upsert(any()) } just Runs

        // Pre-create a fake photo dir for the victim so we can verify it gets deleted.
        val victimDir = File(tempFolder.root, "victim").apply { mkdirs() }
        File(victimDir, "front.jpg").writeBytes(byteArrayOf(0xFF.toByte()))

        val result = repo().create(
            scryfallId = "abc",
            cardName = "Test",
            setName = "Set",
            rarity = null,
            isFoil = false,
            condition = CardCondition.NM,
            startingBidCents = 500L,
            packageWeightOz = 1.0f,
            title = "Test",
        )

        assertEquals(1, result.evictedCount)
        coVerify(exactly = 1) { dao.delete(victim) }
        assertFalse("Victim photo dir should be deleted", victimDir.exists())
    }

    @Test
    fun savePhoto_writesFile_andUpdatesPath() = runBlocking {
        val existing = sampleDraft(id = "d1")
        coEvery { dao.getById("d1") } returns existing
        val captured = slot<Draft>()
        coEvery { dao.update(capture(captured)) } just Runs

        val source = ByteArrayInputStream(byteArrayOf(1, 2, 3, 4))
        val result = repo().savePhoto("d1", PhotoFace.FRONT, source)

        assertNotNull(result)
        val expectedPath = File(File(tempFolder.root, "d1"), "front.jpg").absolutePath
        assertEquals(expectedPath, result!!.frontPhotoPath)
        assertEquals(expectedPath, captured.captured.frontPhotoPath)
        assertEquals(now, captured.captured.updatedAt)
        assertTrue(File(expectedPath).exists())
        assertEquals(4, File(expectedPath).length())
    }

    @Test
    fun savePhoto_backFace_writesToBackJpg() = runBlocking {
        coEvery { dao.getById("d1") } returns sampleDraft(id = "d1")
        coEvery { dao.update(any()) } just Runs

        val result = repo().savePhoto("d1", PhotoFace.BACK, ByteArrayInputStream(byteArrayOf(7)))

        val expectedPath = File(File(tempFolder.root, "d1"), "back.jpg").absolutePath
        assertEquals(expectedPath, result?.backPhotoPath)
        assertNull(result?.frontPhotoPath)
    }

    @Test
    fun savePhoto_missingDraft_returnsNullAndClosesStream() = runBlocking {
        coEvery { dao.getById("ghost") } returns null

        var closed = false
        val source = object : ByteArrayInputStream(byteArrayOf(1)) {
            override fun close() { closed = true; super.close() }
        }

        val result = repo().savePhoto("ghost", PhotoFace.FRONT, source)

        assertNull(result)
        assertTrue("Stream must be closed even when draft is missing", closed)
        // No file written.
        assertFalse(File(File(tempFolder.root, "ghost"), "front.jpg").exists())
    }

    @Test
    fun delete_removesRowAndPhotoDir() = runBlocking {
        val existing = sampleDraft(id = "d1")
        coEvery { dao.getById("d1") } returns existing
        coEvery { dao.delete(existing) } just Runs
        val dir = File(tempFolder.root, "d1").apply { mkdirs() }
        File(dir, "front.jpg").writeBytes(byteArrayOf(1))

        val removed = repo().delete("d1")

        assertTrue(removed)
        coVerify(exactly = 1) { dao.delete(existing) }
        assertFalse(dir.exists())
    }

    @Test
    fun delete_returnsFalseForMissing() = runBlocking {
        coEvery { dao.getById("nope") } returns null
        assertFalse(repo().delete("nope"))
    }

    @Test
    fun pruneStale_removesAndDeletesPhotos() = runBlocking {
        val cutoff = now - DraftRepository.DEFAULT_STALE_TTL_MILLIS
        val oldDraft = sampleDraft(id = "old", updatedAt = cutoff - 1L)
        val freshDraft = sampleDraft(id = "fresh", updatedAt = now)
        coEvery { dao.listAll() } returns listOf(oldDraft, freshDraft)
        coEvery { dao.deleteStale(cutoff) } returns 1
        val oldDir = File(tempFolder.root, "old").apply { mkdirs() }
        File(oldDir, "front.jpg").writeBytes(byteArrayOf(1))
        val freshDir = File(tempFolder.root, "fresh").apply { mkdirs() }
        File(freshDir, "front.jpg").writeBytes(byteArrayOf(1))

        val deleted = repo().pruneStale()

        assertEquals(1, deleted)
        assertFalse(oldDir.exists())
        assertTrue("Fresh draft photos should survive", freshDir.exists())
    }

    private fun sampleDraft(
        id: String,
        updatedAt: Long = now,
    ) = Draft(
        id = id,
        createdAt = updatedAt,
        updatedAt = updatedAt,
        scryfallId = "abc",
        cardName = "Test",
        setName = "Set",
        rarity = null,
        isFoil = false,
        condition = CardCondition.NM,
        startingBidCents = 500L,
        packageWeightOz = 1.0f,
        title = "Test",
    )
}
