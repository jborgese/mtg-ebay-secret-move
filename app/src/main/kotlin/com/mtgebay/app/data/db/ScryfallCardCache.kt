package com.mtgebay.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Cached Scryfall card payload. We store the raw JSON instead of unfolding every
 * Scryfall field into its own column — Scryfall's schema is wide and evolving,
 * and we only ever round-trip the whole card object back to a [com.mtgebay.app.data.scryfall.ScryfallCard].
 */
@Entity(tableName = "scryfall_card_cache")
data class ScryfallCardCache(
    @PrimaryKey val scryfallId: String,
    val json: String,
    /** `System.currentTimeMillis()` at the time of fetch. Used by the 7-day TTL. */
    val fetchedAt: Long,
)
