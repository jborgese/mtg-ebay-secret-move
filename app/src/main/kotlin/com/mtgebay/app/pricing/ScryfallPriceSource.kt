package com.mtgebay.app.pricing

import com.mtgebay.app.data.CardRepository
import kotlin.math.roundToLong

/**
 * Fallback price source backed by Scryfall's embedded TCGplayer prices. Scryfall
 * only publishes a single NM-equivalent price per finish (`prices.usd` for
 * non-foil, `prices.usd_foil` for foil); this source always applies
 * [CONDITION_MULTIPLIERS] to derive non-NM prices, so every non-NM answer is
 * flagged `isEstimated = true`.
 */
class ScryfallPriceSource(
    private val cards: CardRepository,
) : PriceSource {

    override val name: String = "scryfall"

    override suspend fun query(query: PriceQuery): PriceResult? {
        val card = runCatching { cards.getById(query.scryfallId) }.getOrNull() ?: return null
        val raw = if (query.isFoil) card.prices?.usdFoil else card.prices?.usd
        val nmDollars = raw?.toDoubleOrNull() ?: return null
        val nmCents = (nmDollars * 100.0).roundToLong()
        if (nmCents <= 0) return null

        if (query.condition == CardCondition.NM) {
            return PriceResult(marketCents = nmCents, source = name, isEstimated = false)
        }
        val multiplier = CONDITION_MULTIPLIERS[query.condition] ?: return null
        val adjusted = (nmCents * multiplier).roundToLong().coerceAtLeast(1)
        return PriceResult(marketCents = adjusted, source = name, isEstimated = true)
    }
}
