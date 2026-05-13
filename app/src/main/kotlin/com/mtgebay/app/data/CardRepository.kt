package com.mtgebay.app.data

import com.mtgebay.app.data.db.ScryfallCardCache
import com.mtgebay.app.data.db.ScryfallCardDao
import com.mtgebay.app.data.scryfall.ScryfallApi
import com.mtgebay.app.data.scryfall.ScryfallCard
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit

/**
 * Cache-aside repository for Scryfall card metadata.
 *
 * - `getById` reads from the Room cache first. If the cached row is missing
 *   or older than [ttlMillis] (default 7 days), it refreshes from the API and
 *   upserts.
 * - `searchByExactName` and `autocomplete` are network-only — search queries
 *   are short-lived and small, no value in caching individual queries.
 *
 * The [clock] parameter is the `now`-millis source so tests can advance time
 * deterministically.
 */
class CardRepository(
    private val api: ScryfallApi,
    private val dao: ScryfallCardDao,
    private val json: Json = DEFAULT_JSON,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
) {

    suspend fun getById(scryfallId: String): ScryfallCard {
        val cached = dao.getById(scryfallId)
        if (cached != null && clock() - cached.fetchedAt < ttlMillis) {
            return json.decodeFromString(ScryfallCard.serializer(), cached.json)
        }
        val fresh = api.cardById(scryfallId)
        dao.upsert(
            ScryfallCardCache(
                scryfallId = scryfallId,
                json = json.encodeToString(ScryfallCard.serializer(), fresh),
                fetchedAt = clock(),
            ),
        )
        return fresh
    }

    /**
     * Search Scryfall for every printing matching the exact card name. Scryfall
     * search syntax `!"…"` does an exact match; `unique=prints` returns one
     * entry per distinct printing (set + collector_number combination).
     */
    suspend fun searchByExactName(name: String): List<ScryfallCard> =
        api.searchCards(q = """!"$name"""").data

    /** Free-text name autocomplete — Scryfall returns up to ~20 names. */
    suspend fun autocomplete(query: String): List<String> =
        api.autocomplete(q = query).data

    /** Convenience for periodic cleanup; the cleanup worker (Phase 5) will call this. */
    suspend fun pruneStale(): Int = dao.deleteStale(clock() - ttlMillis)

    companion object {
        val DEFAULT_TTL_MILLIS: Long = TimeUnit.DAYS.toMillis(7)

        val DEFAULT_JSON: Json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
            encodeDefaults = true
        }
    }
}
