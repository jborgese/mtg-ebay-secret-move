package com.mtgebay.app.pricing

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Subset of TCGTracking's Open TCG API payloads we care about. Validated
 * against the Magic category (id=1) in May 2026. See
 * https://tcgtracking.com/tcgapi/ for the canonical schema.
 */

@Serializable
data class TcgSetList(
    val sets: List<TcgSet> = emptyList(),
)

@Serializable
data class TcgSet(
    val id: Int,
    val name: String,
    val abbreviation: String? = null,
    @SerialName("is_supplemental") val isSupplemental: Boolean = false,
)

@Serializable
data class TcgSkuResponse(
    @SerialName("set_id") val setId: Int = 0,
    @SerialName("product_count") val productCount: Int = 0,
    @SerialName("sku_count") val skuCount: Int = 0,
    /**
     * Outer key: TCGplayer `product_id` (string). Inner key: SKU id (string).
     * Inner value: per-SKU pricing block.
     */
    val products: Map<String, Map<String, TcgSku>> = emptyMap(),
)

@Serializable
data class TcgSku(
    /** Condition code: "NM", "LP", "MP", "HP", "DMG". */
    val cnd: String,
    /** Variant: "N" = normal/non-foil, "F" = foil. */
    @SerialName("var") val variant: String,
    /** Language: "EN", etc. */
    val lng: String,
    /** Market price (USD), nullable when no listings. */
    val mkt: Double? = null,
    val low: Double? = null,
    val hi: Double? = null,
    /** Active listing count for this SKU. Higher = more representative price. */
    val cnt: Int = 0,
)
