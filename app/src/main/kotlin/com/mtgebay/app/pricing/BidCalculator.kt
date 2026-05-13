package com.mtgebay.app.pricing

import kotlin.math.roundToLong

/**
 * Per spec: the suggested eBay starting bid is 70% of the condition-adjusted
 * market price. Rounds half-up to the nearest cent.
 *
 * Always returns at least 1 cent for any positive market price — eBay rejects
 * $0.00 auctions.
 */
object BidCalculator {

    const val STARTING_BID_FRACTION: Double = 0.70

    fun startingBidCents(marketCents: Long): Long {
        if (marketCents <= 0) return 0
        val raw = (marketCents * STARTING_BID_FRACTION).roundToLong()
        return raw.coerceAtLeast(1)
    }
}
