package com.mtgebay.app.data.scryfall

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Subset of the Scryfall Card object that the app actually consumes. The full
 * Card payload has ~60 fields; we deserialize only what we use and let
 * `Json { ignoreUnknownKeys = true }` (configured in `ScryfallApi`) discard
 * the rest.
 *
 * See https://scryfall.com/docs/api/cards for the canonical schema.
 */
@Serializable
data class ScryfallCard(
    val id: String,
    @SerialName("oracle_id") val oracleId: String? = null,
    val name: String,
    val lang: String? = null,
    @SerialName("released_at") val releasedAt: String? = null,
    val layout: String? = null,
    val set: String,
    @SerialName("set_name") val setName: String,
    @SerialName("collector_number") val collectorNumber: String,
    val rarity: String? = null,
    val finishes: List<String> = emptyList(),
    @SerialName("type_line") val typeLine: String? = null,
    @SerialName("mana_cost") val manaCost: String? = null,
    val cmc: Double? = null,
    val colors: List<String>? = null,
    @SerialName("color_identity") val colorIdentity: List<String>? = null,
    @SerialName("border_color") val borderColor: String? = null,
    @SerialName("full_art") val fullArt: Boolean = false,
    @SerialName("frame_effects") val frameEffects: List<String>? = null,
    @SerialName("promo_types") val promoTypes: List<String>? = null,
    @SerialName("image_uris") val imageUris: ScryfallImageUris? = null,
    @SerialName("card_faces") val cardFaces: List<ScryfallCardFace>? = null,
    val prices: ScryfallPrices? = null,
    @SerialName("tcgplayer_id") val tcgplayerId: Long? = null,
)

@Serializable
data class ScryfallCardFace(
    val name: String,
    @SerialName("image_uris") val imageUris: ScryfallImageUris? = null,
    @SerialName("type_line") val typeLine: String? = null,
    @SerialName("mana_cost") val manaCost: String? = null,
    @SerialName("oracle_text") val oracleText: String? = null,
    val colors: List<String>? = null,
)

@Serializable
data class ScryfallImageUris(
    val small: String? = null,
    val normal: String? = null,
    val large: String? = null,
    val png: String? = null,
    @SerialName("art_crop") val artCrop: String? = null,
    @SerialName("border_crop") val borderCrop: String? = null,
)

@Serializable
data class ScryfallPrices(
    val usd: String? = null,
    @SerialName("usd_foil") val usdFoil: String? = null,
    @SerialName("usd_etched") val usdEtched: String? = null,
    val eur: String? = null,
    val tix: String? = null,
)

/**
 * Response wrapper for paginated list endpoints (e.g. `/cards/search`).
 * Scryfall returns `{ "object": "list", "data": [...], "has_more": true|false,
 * "next_page": "..." }`. We retain enough to follow pagination if we ever want
 * to; for v1 we stop after the first page.
 */
@Serializable
data class ScryfallList(
    @SerialName("total_cards") val totalCards: Int? = null,
    @SerialName("has_more") val hasMore: Boolean = false,
    @SerialName("next_page") val nextPage: String? = null,
    val data: List<ScryfallCard> = emptyList(),
)

/** Response shape for `/cards/autocomplete?q=...`. */
@Serializable
data class ScryfallAutocompleteResponse(
    @SerialName("total_values") val totalValues: Int? = null,
    val data: List<String> = emptyList(),
)
