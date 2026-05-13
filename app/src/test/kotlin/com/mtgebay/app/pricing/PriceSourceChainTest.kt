package com.mtgebay.app.pricing

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException

class PriceSourceChainTest {

    private val primary: PriceSource = mockk()
    private val fallback: PriceSource = mockk()
    private val query = PriceQuery(
        scryfallId = "id1",
        tcgplayerId = 100,
        setCode = "tst",
        collectorNumber = "1",
        isFoil = false,
        condition = CardCondition.NM,
    )

    init {
        // Default `name` getter for log messages.
        coEvery { primary.name } returns "primary"
        coEvery { fallback.name } returns "fallback"
    }

    @Test
    fun primarySucceeds_returnsPrimaryResult_doesNotCallFallback() = runBlocking {
        coEvery { primary.query(query) } returns PriceResult(500, "primary", isEstimated = false)

        val chain = PriceSourceChain(listOf(primary, fallback))
        val result = chain.query(query)

        assertEquals(500L, result?.marketCents)
        assertEquals("primary", result?.source)
        // fallback.query should never be called; not stubbing it means a call would crash.
    }

    @Test
    fun primaryReturnsNull_fallsThroughToFallback() = runBlocking {
        coEvery { primary.query(query) } returns null
        coEvery { fallback.query(query) } returns PriceResult(420, "fallback", isEstimated = true)

        val result = PriceSourceChain(listOf(primary, fallback)).query(query)

        assertEquals(420L, result?.marketCents)
        assertEquals("fallback", result?.source)
    }

    @Test
    fun primaryThrowsIOException_fallsThroughToFallback() = runBlocking {
        coEvery { primary.query(query) } throws IOException("network down")
        coEvery { fallback.query(query) } returns PriceResult(420, "fallback", isEstimated = false)

        val result = PriceSourceChain(listOf(primary, fallback)).query(query)

        assertEquals(420L, result?.marketCents)
    }

    @Test
    fun bothNull_returnsNull() = runBlocking {
        coEvery { primary.query(query) } returns null
        coEvery { fallback.query(query) } returns null

        assertNull(PriceSourceChain(listOf(primary, fallback)).query(query))
    }

    @Test
    fun emptyChain_returnsNull() = runBlocking {
        assertNull(PriceSourceChain(emptyList()).query(query))
    }
}
