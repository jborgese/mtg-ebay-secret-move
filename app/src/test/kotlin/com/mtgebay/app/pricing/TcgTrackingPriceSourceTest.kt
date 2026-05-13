package com.mtgebay.app.pricing

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TcgTrackingPriceSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: TcgTrackingPriceSource

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val api = TcgTrackingApi.create(baseUrl = server.url("/").toString())
        source = TcgTrackingPriceSource(api)
    }

    @After fun tearDown() { server.shutdown() }

    @Test
    fun nmNonFoil_picksHighestCntMatchingSku() = runBlocking {
        enqueueSetList()
        enqueueSkus(SKUS_NM_5USD_AND_NM_LOW_CNT)

        val result = source.query(
            PriceQuery(
                scryfallId = "any",
                tcgplayerId = 100,
                setCode = "tst",
                collectorNumber = "1",
                isFoil = false,
                condition = CardCondition.NM,
            ),
        )

        // 500 cents = $5.00, from the cnt=25 SKU (preferred over the cnt=1 dup).
        assertEquals(500L, result?.marketCents)
        assertEquals("tcgtracking", result?.source)
        assertFalse(result!!.isEstimated)
    }

    @Test
    fun condition_LP_returnsExactMatchWhenAvailable() = runBlocking {
        enqueueSetList()
        enqueueSkus(SKUS_WITH_NM_AND_LP)

        val result = source.query(query(condition = CardCondition.LP, foil = false))

        // LP SKU is 450 cents = $4.50.
        assertEquals(450L, result?.marketCents)
        assertFalse(result!!.isEstimated)
    }

    @Test
    fun condition_HP_fallsBackToNmTimesMultiplier_whenHpHasNoListing() = runBlocking {
        enqueueSetList()
        enqueueSkus(SKUS_WITH_NM_AND_LP)  // No HP entry with price.

        val result = source.query(query(condition = CardCondition.HP, foil = false))

        // NM 500 × 0.50 = 250 cents (HP multiplier).
        assertEquals(250L, result?.marketCents)
        assertTrue(result!!.isEstimated)
    }

    @Test
    fun foil_usesFoilVariantSkus() = runBlocking {
        enqueueSetList()
        enqueueSkus(SKUS_WITH_FOIL)

        val result = source.query(query(condition = CardCondition.NM, foil = true))

        assertEquals(1500L, result?.marketCents)
    }

    @Test
    fun missingTcgplayerId_returnsNullWithoutFetching() = runBlocking {
        val result = source.query(
            PriceQuery(
                scryfallId = "any",
                tcgplayerId = null,
                setCode = "tst",
                collectorNumber = "1",
                isFoil = false,
                condition = CardCondition.NM,
            ),
        )
        assertNull(result)
        // No HTTP traffic should have hit the server.
        assertEquals(0, server.requestCount)
    }

    @Test
    fun setNotInTcgTrackingMap_returnsNull() = runBlocking {
        enqueueSetList()  // includes "tst" set; query a different one.
        val result = source.query(query(setCode = "unknown", condition = CardCondition.NM, foil = false))
        assertNull(result)
    }

    @Test
    fun bothCachesHit_secondIdenticalQueryMakesNoExtraRequests() = runBlocking {
        enqueueSetList()
        enqueueSkus(SKUS_NM_5USD_AND_NM_LOW_CNT)

        source.query(query(condition = CardCondition.NM, foil = false))
        source.query(query(condition = CardCondition.NM, foil = false))

        // First query: 1x /sets + 1x /skus. Second query hits both caches → 0 new requests.
        val paths = (0 until server.requestCount).map { server.takeRequest().path ?: "" }
        assertEquals(2, paths.size)
        assertEquals(1, paths.count { it.endsWith("/sets") })
        assertEquals(1, paths.count { it.contains("/skus") })
    }

    @Test
    fun productNotInSkuResponse_returnsNull() = runBlocking {
        enqueueSetList()
        enqueueSkus("""{"set_id":42,"products":{}}""")

        val result = source.query(query(condition = CardCondition.NM, foil = false))
        assertNull(result)
    }

    private fun query(
        scryfallId: String = "id1",
        tcgplayerId: Long = 100,
        setCode: String = "tst",
        condition: CardCondition,
        foil: Boolean,
    ) = PriceQuery(
        scryfallId = scryfallId,
        tcgplayerId = tcgplayerId,
        setCode = setCode,
        collectorNumber = "1",
        isFoil = foil,
        condition = condition,
    )

    private fun enqueueSetList() {
        server.enqueue(MockResponse().setBody(SET_LIST_RESPONSE).setHeader("Content-Type", "application/json"))
    }

    private fun enqueueSkus(body: String) {
        server.enqueue(MockResponse().setBody(body).setHeader("Content-Type", "application/json"))
    }

    private companion object {
        const val SET_LIST_RESPONSE = """
            {
              "sets": [
                { "id": 42, "name": "Test Set", "abbreviation": "TST", "is_supplemental": false }
              ]
            }
        """

        // tcgplayer_id=100 has two NM/normal SKUs: one with high cnt ($5.00, 25 listings),
        // one with low cnt ($10.00, 1 listing). We should prefer the high-cnt one.
        const val SKUS_NM_5USD_AND_NM_LOW_CNT = """
            {
              "set_id": 42,
              "products": {
                "100": {
                  "9001": { "cnd": "NM", "var": "N", "lng": "EN", "mkt": 5.00, "cnt": 25 },
                  "9002": { "cnd": "NM", "var": "N", "lng": "EN", "mkt": 10.00, "cnt": 1 }
                }
              }
            }
        """

        const val SKUS_WITH_NM_AND_LP = """
            {
              "set_id": 42,
              "products": {
                "100": {
                  "9001": { "cnd": "NM", "var": "N", "lng": "EN", "mkt": 5.00, "cnt": 25 },
                  "9002": { "cnd": "LP", "var": "N", "lng": "EN", "mkt": 4.50, "cnt": 10 }
                }
              }
            }
        """

        const val SKUS_WITH_FOIL = """
            {
              "set_id": 42,
              "products": {
                "100": {
                  "9001": { "cnd": "NM", "var": "N", "lng": "EN", "mkt": 5.00, "cnt": 25 },
                  "9101": { "cnd": "NM", "var": "F", "lng": "EN", "mkt": 15.00, "cnt": 10 }
                }
              }
            }
        """
    }
}
