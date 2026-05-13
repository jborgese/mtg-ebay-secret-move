package com.mtgebay.app.data.scryfall

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ScryfallApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: ScryfallApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = ScryfallApi.create(baseUrl = server.url("/").toString())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun searchCards_parsesAValidListPayload() = runBlocking {
        server.enqueue(MockResponse().setBody(SEARCH_RESPONSE).setHeader("Content-Type", "application/json"))

        val result = api.searchCards(q = """!"Lightning Bolt"""")

        assertEquals(2, result.data.size)
        assertEquals("Lightning Bolt", result.data[0].name)
        assertEquals("lea", result.data[0].set)
        assertEquals("161", result.data[0].collectorNumber)
        assertEquals("common", result.data[0].rarity)
        assertEquals(listOf("nonfoil"), result.data[0].finishes)
        assertEquals(false, result.hasMore)

        val recorded = server.takeRequest()
        val path = recorded.path ?: ""
        assertTrue("URL should hit /cards/search: $path", path.startsWith("/cards/search"))
        assertTrue("Should include exact-name query: $path", path.contains("q=") && path.contains("Lightning"))
        assertTrue("Should request unique=prints: $path", path.contains("unique=prints"))
        assertTrue("Should set User-Agent", recorded.getHeader("User-Agent")?.contains("mtg-ebay") == true)
    }

    @Test
    fun autocomplete_parsesAStringArrayResponse() = runBlocking {
        server.enqueue(MockResponse().setBody(AUTOCOMPLETE_RESPONSE).setHeader("Content-Type", "application/json"))

        val result = api.autocomplete(q = "doubli")

        assertEquals(listOf("Doubling Cube", "Doubling Season", "Doublecast"), result.data)
        assertEquals(3, result.totalValues)
    }

    @Test
    fun cardById_parsesASingleCardPayload() = runBlocking {
        server.enqueue(MockResponse().setBody(SINGLE_CARD_RESPONSE).setHeader("Content-Type", "application/json"))

        val card = api.cardById("c1d2e3f4-0000-0000-0000-000000000001")

        assertEquals("Doubling Season", card.name)
        assertEquals("fdn", card.set)
        assertEquals("216", card.collectorNumber)
        assertEquals("mythic", card.rarity)
        assertEquals(listOf("nonfoil", "foil"), card.finishes)
        assertEquals("13.42", card.prices?.usd)
    }

    @Test
    fun ignoresUnknownFields() = runBlocking {
        // Scryfall payload sprinkled with fields we don't model.
        val payload = """
            {
              "id": "abc-123",
              "name": "Test Card",
              "set": "tst",
              "set_name": "Test Set",
              "collector_number": "1",
              "some_future_field": { "nested": [1, 2, 3] },
              "another_unknown": "ignored"
            }
        """.trimIndent()
        server.enqueue(MockResponse().setBody(payload).setHeader("Content-Type", "application/json"))

        val card = api.cardById("abc-123")
        assertEquals("Test Card", card.name)
        assertEquals("tst", card.set)
    }

    private companion object {
        // Truncated real-shape fixtures — enough fields to exercise the DTOs.
        const val SEARCH_RESPONSE = """
            {
              "object": "list",
              "total_cards": 2,
              "has_more": false,
              "data": [
                {
                  "id": "11111111-1111-1111-1111-111111111111",
                  "name": "Lightning Bolt",
                  "lang": "en",
                  "set": "lea",
                  "set_name": "Limited Edition Alpha",
                  "collector_number": "161",
                  "rarity": "common",
                  "finishes": ["nonfoil"],
                  "image_uris": {
                    "small": "https://example.com/small.jpg",
                    "normal": "https://example.com/normal.jpg"
                  }
                },
                {
                  "id": "22222222-2222-2222-2222-222222222222",
                  "name": "Lightning Bolt",
                  "lang": "en",
                  "set": "m11",
                  "set_name": "Magic 2011",
                  "collector_number": "149",
                  "rarity": "common",
                  "finishes": ["nonfoil", "foil"]
                }
              ]
            }
        """

        const val AUTOCOMPLETE_RESPONSE = """
            {
              "object": "catalog",
              "total_values": 3,
              "data": ["Doubling Cube", "Doubling Season", "Doublecast"]
            }
        """

        const val SINGLE_CARD_RESPONSE = """
            {
              "id": "c1d2e3f4-0000-0000-0000-000000000001",
              "name": "Doubling Season",
              "lang": "en",
              "released_at": "2024-11-15",
              "set": "fdn",
              "set_name": "Foundations",
              "collector_number": "216",
              "rarity": "mythic",
              "finishes": ["nonfoil", "foil"],
              "image_uris": {
                "normal": "https://example.com/ds-normal.jpg"
              },
              "prices": {
                "usd": "13.42",
                "usd_foil": "19.50"
              }
            }
        """
    }
}
