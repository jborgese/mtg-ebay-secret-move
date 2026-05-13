package com.mtgebay.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.mtgebay.app.pricing.CardCondition

/**
 * A draft eBay listing. Built up as the user picks a printing, sets condition
 * and finish, captures photos, and (in Phase 6) attempts to publish. The same
 * row is reused for retries; only deleted on successful publish, user delete,
 * or the 30-day cleanup worker.
 */
@Entity(tableName = "drafts")
data class Draft(
    /** UUID4 string generated when the draft is first created. */
    @PrimaryKey val id: String,

    val createdAt: Long,
    val updatedAt: Long,

    /** Last publish attempt's error message; null while we've never tried or after a successful publish prep. */
    val lastError: String? = null,
    val retryCount: Int = 0,

    val scryfallId: String,
    val cardName: String,
    val setName: String,
    val rarity: String? = null,

    val isFoil: Boolean,
    val condition: CardCondition,

    val startingBidCents: Long,

    /** Shipping package weight in ounces. UI choices: 0.5, 1, 2, 4. */
    val packageWeightOz: Float,

    /** Local file paths under `context.filesDir/photos/{draftId}/`. Null until the user captures. */
    val frontPhotoPath: String? = null,
    val backPhotoPath: String? = null,

    /** Auto-generated eBay listing title (≤ 80 chars; user-editable on review). */
    val title: String,
)
