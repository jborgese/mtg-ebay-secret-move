package com.mtgebay.app.pricing

import com.mtgebay.app.data.CardRepository
import com.mtgebay.app.data.scryfall.ScryfallCard
import com.mtgebay.app.data.scryfall.ScryfallPrices
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScryfallPriceSourceTest {

    private val cards: CardRepository = mockk()
    private val source = ScryfallPriceSource(cards)

    @Test fun nmNonFoil_returnsExactCentsWithoutEstimation() = runBlocking {
        givenCard("id1", usd = "8.00")

        val result = source.query(query(condition = CardCondition.NM, foil = false))

        assertEquals(800L, result?.marketCents)
        assertEquals("scryfall", result?.source)
        assertFalse(result!!.isEstimated)
    }

    @Test fun nmFoil_usesFoilPrice() = runBlocking {
        givenCard("id1", usd = "8.00", usdFoil = "20.00")

        val result = source.query(query(condition = CardCondition.NM, foil = true))

        assertEquals(2000L, result?.marketCents)
        assertFalse(result!!.isEstimated)
    }

    @Test fun lpNonFoil_appliesEightyFivePercentMultiplier() = runBlocking {
        givenCard("id1", usd = "10.00")  // 1000 cents

        val result = source.query(query(condition = CardCondition.LP, foil = false))

        // 1000 × 0.85 = 850
        assertEquals(850L, result?.marketCents)
        assertTrue(result!!.isEstimated)
    }

    @Test fun hpNonFoil_appliesHalfMultiplier() = runBlocking {
        givenCard("id1", usd = "10.00")
        val result = source.query(query(condition = CardCondition.HP, foil = false))
        assertEquals(500L, result?.marketCents)
        assertTrue(result!!.isEstimated)
    }

    @Test fun missingCard_returnsNull() = runBlocking {
        coEvery { cards.getById("absent") } throws RuntimeException("404")
        val result = source.query(query(scryfallId = "absent", condition = CardCondition.NM, foil = false))
        assertNull(result)
    }

    @Test fun missingPrice_returnsNull() = runBlocking {
        givenCard("id1", usd = null)
        assertNull(source.query(query(condition = CardCondition.NM, foil = false)))
    }

    @Test fun missingFoilPrice_returnsNull() = runBlocking {
        givenCard("id1", usd = "5.00", usdFoil = null)
        assertNull(source.query(query(condition = CardCondition.NM, foil = true)))
    }

    @Test fun zeroPrice_returnsNull() = runBlocking {
        givenCard("id1", usd = "0.00")
        assertNull(source.query(query(condition = CardCondition.NM, foil = false)))
    }

    private fun givenCard(id: String, usd: String?, usdFoil: String? = null) {
        coEvery { cards.getById(id) } returns ScryfallCard(
            id = id,
            name = "Test Card",
            set = "tst",
            setName = "Test Set",
            collectorNumber = "1",
            prices = ScryfallPrices(usd = usd, usdFoil = usdFoil),
        )
    }

    private fun query(
        scryfallId: String = "id1",
        condition: CardCondition,
        foil: Boolean,
    ) = PriceQuery(
        scryfallId = scryfallId,
        tcgplayerId = null,
        setCode = "tst",
        collectorNumber = "1",
        isFoil = foil,
        condition = condition,
    )
}
