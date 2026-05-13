package com.mtgebay.app.pricing

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit
import kotlin.math.roundToLong

/**
 * Primary [PriceSource]. Resolves a Scryfall set code to a TCGTracking `set_id`,
 * fetches the per-set SKU pricing dump, and selects the SKU matching the
 * requested condition + variant + EN language. If no condition-specific SKU
 * has listings, falls back to NM × [CONDITION_MULTIPLIERS].
 *
 * Provider-recommended caching:
 *   - Static set list: ≥ 7 days
 *   - Pricing / SKU data: 1 day
 *
 * Both caches live in-memory (a single per-process [TcgTrackingPriceSource]
 * is enough for a personal-use app). A Mutex serializes the fetch path so
 * concurrent queries don't trigger redundant network calls.
 */
class TcgTrackingPriceSource(
    private val api: TcgTrackingApi,
    private val clock: () -> Long = System::currentTimeMillis,
    private val setListTtlMillis: Long = TimeUnit.DAYS.toMillis(7),
    private val skuTtlMillis: Long = TimeUnit.DAYS.toMillis(1),
) : PriceSource {

    override val name: String = "tcgtracking"

    private val setMapMutex = Mutex()
    private var cachedSetMap: Map<String, Int>? = null
    private var setMapFetchedAt: Long = 0L

    private val skuMutex = Mutex()
    private val skuCache = mutableMapOf<Int, CachedSkus>()

    private data class CachedSkus(val response: TcgSkuResponse, val fetchedAt: Long)

    override suspend fun query(query: PriceQuery): PriceResult? {
        val productId = query.tcgplayerId?.toString() ?: return null

        val setMap = setMap() ?: return null
        val setId = setMap[query.setCode.lowercase()] ?: return null

        val skuResp = skus(setId) ?: return null
        val productSkus = skuResp.products[productId] ?: return null

        val variant = if (query.isFoil) "F" else "N"
        val englishVariant = productSkus.values.filter { it.lng == "EN" && it.variant == variant && it.mkt != null }
        if (englishVariant.isEmpty()) return null

        // Group by condition code so we can locate the exact match and the NM fallback in one pass.
        val byCondition = englishVariant.groupBy { it.cnd }

        // Exact condition: pick the SKU with the most listings (most representative market).
        byCondition[query.condition.tcgCode]?.maxByOrNull { it.cnt }?.let { sku ->
            sku.mkt?.let { return PriceResult(
                marketCents = dollarsToCents(it),
                source = name,
                isEstimated = false,
            ) }
        }

        // Fall back: scale NM by the per-spec condition multiplier.
        val nmSku = byCondition["NM"]?.maxByOrNull { it.cnt } ?: return null
        val nmCents = nmSku.mkt?.let { dollarsToCents(it) } ?: return null
        val multiplier = CONDITION_MULTIPLIERS[query.condition] ?: return null
        val adjusted = (nmCents * multiplier).roundToLong().coerceAtLeast(1)
        return PriceResult(marketCents = adjusted, source = name, isEstimated = true)
    }

    private suspend fun setMap(): Map<String, Int>? = setMapMutex.withLock {
        cachedSetMap?.takeIf { clock() - setMapFetchedAt < setListTtlMillis }?.let { return@withLock it }
        try {
            val resp = api.sets(TcgTrackingApi.MAGIC_CATEGORY)
            val map = resp.sets
                .asSequence()
                .filter { !it.abbreviation.isNullOrBlank() }
                .associate { it.abbreviation!!.lowercase() to it.id }
            cachedSetMap = map
            setMapFetchedAt = clock()
            map
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun skus(setId: Int): TcgSkuResponse? = skuMutex.withLock {
        skuCache[setId]?.takeIf { clock() - it.fetchedAt < skuTtlMillis }?.let { return@withLock it.response }
        try {
            val resp = api.skus(TcgTrackingApi.MAGIC_CATEGORY, setId)
            skuCache[setId] = CachedSkus(resp, clock())
            resp
        } catch (_: Exception) {
            null
        }
    }

    private fun dollarsToCents(d: Double): Long = (d * 100.0).roundToLong()
}
