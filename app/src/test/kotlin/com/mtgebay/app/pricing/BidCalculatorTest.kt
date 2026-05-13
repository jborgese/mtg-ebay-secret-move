package com.mtgebay.app.pricing

import org.junit.Assert.assertEquals
import org.junit.Test

class BidCalculatorTest {

    @Test fun zero_returnsZero() {
        assertEquals(0, BidCalculator.startingBidCents(0))
    }

    @Test fun negative_returnsZero() {
        assertEquals(0, BidCalculator.startingBidCents(-100))
    }

    @Test fun eightDollars_returnsFiveSixty() {
        // $8.00 → 800 cents × 0.70 = 560 cents = $5.60 (matches the spec example).
        assertEquals(560, BidCalculator.startingBidCents(800))
    }

    @Test fun roundsHalfToEvenOrHalfUp() {
        // 100 × 0.70 = 70.0 → 70.
        assertEquals(70, BidCalculator.startingBidCents(100))
        // 101 × 0.70 = 70.7 → 71.
        assertEquals(71, BidCalculator.startingBidCents(101))
        // 99 × 0.70 = 69.3 → 69.
        assertEquals(69, BidCalculator.startingBidCents(99))
    }

    @Test fun coercesAtLeastOneCent() {
        // Any positive market should produce at least a 1-cent bid (eBay rejects $0).
        assertEquals(1, BidCalculator.startingBidCents(1))
    }
}
