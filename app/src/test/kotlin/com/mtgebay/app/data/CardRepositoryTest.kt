package com.mtgebay.app.data

import com.mtgebay.app.data.db.ScryfallCardCache
import com.mtgebay.app.data.db.ScryfallCardDao
import com.mtgebay.app.data.scryfall.ScryfallApi
import com.mtgebay.app.data.scryfall.ScryfallAutocompleteResponse
import com.mtgebay.app.data.scryfall.ScryfallCard
import com.mtgebay.app.data.scryfall.ScryfallList
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.TimeUnit

class CardRepositoryTest {

    private val api: ScryfallApi = mockk()
    private val dao: ScryfallCardDao = mockk()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    /** Mutable clock so tests can advance time. */
    private var now: Long = 1_000_000L

    private fun newRepo(ttlMillis: Long = TimeUnit.DAYS.toMillis(7)): CardRepository =
        CardRepository(api, dao, json, clock = { now }, ttlMillis = ttlMillis)

    @Test
    fun cacheHit_returnsCachedCard_doesNotCallApi() = runBlocking {
        val cached = sampleCard("abc-123", name = "Lightning Bolt")
        coEvery { dao.getById("abc-123") } returns ScryfallCardCache(
            scryfallId = "abc-123",
            json = json.encodeToString(ScryfallCard.serializer(), cached),
            fetchedAt = now - TimeUnit.HOURS.toMillis(1),  // 1 hour old → fresh
        )

        val result = newRepo().getById("abc-123")

        assertEquals("Lightning Bolt", result.name)
        coVerify(exactly = 0) { api.cardById(any()) }
        coVerify(exactly = 0) { dao.upsert(any()) }
    }

    @Test
    fun cacheMiss_callsApiAndUpserts() = runBlocking {
        coEvery { dao.getById("missing-id") } returns null
        val fresh = sampleCard("missing-id", name = "Doubling Season")
        coEvery { api.cardById("missing-id") } returns fresh
        coEvery { dao.upsert(any()) } just Runs

        val result = newRepo().getById("missing-id")

        assertEquals("Doubling Season", result.name)
        coVerify(exactly = 1) { api.cardById("missing-id") }
        coVerify(exactly = 1) {
            dao.upsert(match {
                it.scryfallId == "missing-id" && it.fetchedAt == now && it.json.contains("Doubling Season")
            })
        }
    }

    @Test
    fun staleCache_refetches() = runBlocking {
        val ttl = TimeUnit.DAYS.toMillis(7)
        val cached = sampleCard("stale-id", name = "Stale Card")
        coEvery { dao.getById("stale-id") } returns ScryfallCardCache(
            scryfallId = "stale-id",
            json = json.encodeToString(ScryfallCard.serializer(), cached),
            fetchedAt = now - ttl - 1,  // just past TTL
        )
        val refreshed = sampleCard("stale-id", name = "Refreshed Card")
        coEvery { api.cardById("stale-id") } returns refreshed
        coEvery { dao.upsert(any()) } just Runs

        val result = newRepo(ttl).getById("stale-id")

        assertEquals("Refreshed Card", result.name)
        coVerify(exactly = 1) { api.cardById("stale-id") }
    }

    @Test
    fun searchByExactName_passesQuotedNameToApi() = runBlocking {
        val cards = listOf(
            sampleCard("p1", name = "Erode", set = "stx"),
            sampleCard("p2", name = "Erode", set = "sos"),
        )
        coEvery { api.searchCards(q = """!"Erode"""", any(), any(), any()) } returns
            ScryfallList(totalCards = 2, data = cards)

        val results = newRepo().searchByExactName("Erode")

        assertEquals(2, results.size)
        assertEquals("stx", results[0].set)
        coVerify { api.searchCards(q = """!"Erode"""", any(), any(), any()) }
    }

    @Test
    fun autocomplete_delegatesToApi() = runBlocking {
        coEvery { api.autocomplete("doubl") } returns
            ScryfallAutocompleteResponse(totalValues = 2, data = listOf("Doubling Season", "Doublecast"))

        val names = newRepo().autocomplete("doubl")

        assertEquals(listOf("Doubling Season", "Doublecast"), names)
    }

    @Test
    fun pruneStale_callsDaoWithComputedCutoff() = runBlocking {
        val ttl = TimeUnit.DAYS.toMillis(7)
        coEvery { dao.deleteStale(now - ttl) } returns 5

        val deleted = newRepo(ttl).pruneStale()

        assertEquals(5, deleted)
        coVerify { dao.deleteStale(now - ttl) }
    }

    private fun sampleCard(
        id: String,
        name: String,
        set: String = "tst",
    ): ScryfallCard = ScryfallCard(
        id = id,
        name = name,
        set = set,
        setName = "Test Set",
        collectorNumber = "1",
    )
}
