package com.mtgebay.app.data

import com.mtgebay.app.data.db.Draft
import com.mtgebay.app.data.db.DraftDao
import com.mtgebay.app.pricing.CardCondition
import kotlinx.coroutines.flow.Flow
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.TimeUnit

enum class PhotoFace(val fileName: String) {
    FRONT("front"),
    BACK("back"),
}

/**
 * Coordinates draft listings with their on-disk photo files.
 *
 * Responsibilities:
 *  - Generate UUID-keyed [Draft] rows.
 *  - Stream camera-captured JPEGs to `${photosDir}/${draftId}/{front|back}.jpg`
 *    and update the row's `*PhotoPath`.
 *  - Enforce a **soft cap** of [softCap] drafts: on each create, if the table
 *    already holds ≥ [softCap] rows, evict the oldest (by `updatedAt`) along
 *    with its photos. Returns the eviction count from [create] so the UI can
 *    surface a one-time warning.
 *  - On delete, prune the row and the entire `${draftId}/` photo directory.
 *  - Expose [pruneStale] for the 30-day cleanup worker (Phase 5c).
 */
class DraftRepository(
    private val dao: DraftDao,
    private val photosDir: File,
    private val now: () -> Long = System::currentTimeMillis,
    private val softCap: Int = DEFAULT_SOFT_CAP,
) {

    data class CreateResult(val draft: Draft, val evictedCount: Int)

    /** Stream the drafts table newest-first. */
    fun observeAll(): Flow<List<Draft>> = dao.observeAll()

    suspend fun get(id: String): Draft? = dao.getById(id)

    /**
     * Create a new draft. Evicts the oldest drafts (with their photos) when the
     * table is over the soft cap before insert. Returns the inserted draft and
     * how many older drafts were dropped.
     */
    suspend fun create(
        scryfallId: String,
        cardName: String,
        setName: String,
        rarity: String?,
        isFoil: Boolean,
        condition: CardCondition,
        startingBidCents: Long,
        packageWeightOz: Float,
        title: String,
    ): CreateResult {
        val evicted = enforceCap()
        val nowMillis = now()
        val draft = Draft(
            id = UUID.randomUUID().toString(),
            createdAt = nowMillis,
            updatedAt = nowMillis,
            scryfallId = scryfallId,
            cardName = cardName,
            setName = setName,
            rarity = rarity,
            isFoil = isFoil,
            condition = condition,
            startingBidCents = startingBidCents,
            packageWeightOz = packageWeightOz,
            title = title,
        )
        dao.upsert(draft)
        return CreateResult(draft = draft, evictedCount = evicted)
    }

    /** Update [draft] verbatim; we bump `updatedAt` to keep ordering monotonic. */
    suspend fun update(draft: Draft): Draft {
        val refreshed = draft.copy(updatedAt = now())
        dao.update(refreshed)
        return refreshed
    }

    /**
     * Persist [source] as `${draftId}/{face}.jpg` and update the matching column
     * on the draft. Returns the updated draft or null if the draft doesn't exist.
     * The [source] stream is closed by this function.
     */
    suspend fun savePhoto(draftId: String, face: PhotoFace, source: InputStream): Draft? {
        val draft = dao.getById(draftId) ?: run {
            source.close()
            return null
        }
        val dir = File(photosDir, draftId).apply { mkdirs() }
        val out = File(dir, "${face.fileName}.jpg")
        source.use { input -> out.outputStream().use { stream -> input.copyTo(stream) } }
        val path = out.absolutePath
        val nowMillis = now()
        val updated = when (face) {
            PhotoFace.FRONT -> draft.copy(frontPhotoPath = path, updatedAt = nowMillis)
            PhotoFace.BACK -> draft.copy(backPhotoPath = path, updatedAt = nowMillis)
        }
        dao.update(updated)
        return updated
    }

    /** Delete the draft and its photo directory. Returns true iff the draft existed. */
    suspend fun delete(id: String): Boolean {
        val draft = dao.getById(id) ?: return false
        dao.delete(draft)
        deletePhotosFor(id)
        return true
    }

    /** Periodic cleanup: drop drafts older than [ttlMillis]; remove their photo files. */
    suspend fun pruneStale(ttlMillis: Long = DEFAULT_STALE_TTL_MILLIS): Int {
        val cutoff = now() - ttlMillis
        val stale = dao.listAll().filter { it.updatedAt < cutoff }
        if (stale.isEmpty()) return 0
        val deleted = dao.deleteStale(cutoff)
        stale.forEach { deletePhotosFor(it.id) }
        return deleted
    }

    /** If we're at or above [softCap], delete the oldest rows until count == [softCap]-1. */
    private suspend fun enforceCap(): Int {
        val count = dao.count()
        val overage = count - (softCap - 1)
        if (overage <= 0) return 0
        val victims = dao.oldest(overage)
        victims.forEach { draft ->
            dao.delete(draft)
            deletePhotosFor(draft.id)
        }
        return victims.size
    }

    private fun deletePhotosFor(draftId: String) {
        val dir = File(photosDir, draftId)
        if (dir.exists()) dir.deleteRecursively()
    }

    companion object {
        const val DEFAULT_SOFT_CAP: Int = 50
        val DEFAULT_STALE_TTL_MILLIS: Long = TimeUnit.DAYS.toMillis(30)
    }
}
