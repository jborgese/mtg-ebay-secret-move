package com.mtgebay.app.pricing

/**
 * Card condition as encoded by eBay item specifics and the TCGplayer SKU vocabulary.
 * Order matches `min market value` ranking (NM is the most pristine, DMG the worst).
 */
enum class CardCondition(val display: String, val tcgCode: String) {
    NM("Near Mint", "NM"),
    LP("Lightly Played", "LP"),
    MP("Moderately Played", "MP"),
    HP("Heavily Played", "HP"),
    DMG("Damaged", "DMG"),
}

/**
 * Per-spec fallback multipliers used when condition-specific market data is missing.
 * Applied to the NM market price.
 */
val CONDITION_MULTIPLIERS: Map<CardCondition, Double> = mapOf(
    CardCondition.NM to 1.00,
    CardCondition.LP to 0.85,
    CardCondition.MP to 0.70,
    CardCondition.HP to 0.50,
    CardCondition.DMG to 0.30,
)

/**
 * The market price for a specific (printing, condition, foil) combination.
 *
 * - `marketCents` is the condition-adjusted market value in USD cents.
 * - `source` is the provider that produced the answer ("tcgtracking" / "scryfall").
 * - `isEstimated` is `true` when the source had no condition-specific listing and
 *   we applied a [CONDITION_MULTIPLIERS] fallback to the NM price.
 */
data class PriceResult(
    val marketCents: Long,
    val source: String,
    val isEstimated: Boolean,
)

/**
 * A query bundles the identifiers needed to look up a printing across any of the
 * supported pricing providers. Not every provider uses every field:
 *  - TCGTracking keys on `tcgplayerId` (its `product_id` IS the TCGplayer ID).
 *  - Scryfall is keyed on `scryfallId` (we read the cached card's `prices` field).
 */
data class PriceQuery(
    val scryfallId: String,
    val tcgplayerId: Long?,
    val setCode: String,
    val collectorNumber: String,
    val isFoil: Boolean,
    val condition: CardCondition,
)

/** Read-only interface for fetching market prices. Implementations may cache internally. */
interface PriceSource {
    val name: String
    suspend fun query(query: PriceQuery): PriceResult?
}
