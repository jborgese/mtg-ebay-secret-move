package com.mtgebay.app.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * Integration tests for [PhashDb] that operate on the real 3.98 MB asset and
 * use the same 10 golden fixtures as [PhasherTest]. Each fixture's
 * `meta.txt` records the `scryfall_id` and `expected_phash`, which lets us
 * verify the DB returns the right card for every fixture pHash at distance 0.
 */
class PhashDbTest {

    /** Real `phash.bin` from the app assets, located relative to the app module's cwd. */
    private val realDbPath: File = File("src/main/assets/phash.bin")

    @Test
    fun headerReportsExpectedCount() {
        val db = openRealDb()
        // Phase 1 full run wrote 97,145 records; if the generator is rerun this may shift slightly.
        assertTrue(
            "Expected ~97k records, got ${db.recordCount}",
            db.recordCount in 50_000..200_000,
        )
    }

    @Test
    fun everyFixtureLookupFindsItsCardAtDistanceZero() {
        val db = openRealDb()
        for (idx in 0..9) {
            val name = "%02d".format(idx)
            val (expectedHash, expectedId) = loadFixture(name)

            val hits = db.lookup(expectedHash, maxHamming = 0, topN = 5)
            assertFalse("Fixture $name: lookup returned no hits", hits.isEmpty())

            val match = hits.firstOrNull { it.scryfallId == expectedId }
            assertNotNull(
                "Fixture $name: expected scryfall_id $expectedId not in hits at distance 0. " +
                    "Hits: ${hits.joinToString { "${it.scryfallId} d=${it.distance}" }}",
                match,
            )
            assertEquals("Fixture $name: matched record's distance should be 0", 0, match!!.distance)
            assertEquals("Fixture $name: matched record's pHash should equal query", expectedHash, match.phash)
        }
    }

    @Test
    fun lookupRespectsMaxHamming() {
        val db = openRealDb()
        val (expectedHash, _) = loadFixture("00")

        // Flip 3 bits in a known hash; distance to the original record should be exactly 3.
        val perturbed = expectedHash xor 0b111L

        val tight = db.lookup(perturbed, maxHamming = 2, topN = 5)
        assertTrue(
            "Lookup with maxHamming=2 should not return the fixture at distance 3: $tight",
            tight.none { it.phash == expectedHash },
        )

        val loose = db.lookup(perturbed, maxHamming = 3, topN = 5)
        val match = loose.firstOrNull { it.phash == expectedHash }
        assertNotNull("Lookup with maxHamming=3 should include fixture at distance 3", match)
        assertEquals(3, match!!.distance)
    }

    @Test
    fun lookupOrderedByDistanceAscending() {
        val db = openRealDb()
        val (expectedHash, _) = loadFixture("00")
        // Big radius to force multiple candidates.
        val hits = db.lookup(expectedHash, maxHamming = 20, topN = 5)
        for (i in 1 until hits.size) {
            assertTrue(
                "Hits should be distance-ascending; got ${hits.map { it.distance }}",
                hits[i].distance >= hits[i - 1].distance,
            )
        }
    }

    @Test
    fun topNCapsResultsEvenWithLargeRadius() {
        val db = openRealDb()
        val (expectedHash, _) = loadFixture("00")
        val hits = db.lookup(expectedHash, maxHamming = 32, topN = 3)
        assertTrue("Expected at most 3 results, got ${hits.size}", hits.size <= 3)
    }

    @Test
    fun emptyResultWhenNothingWithinRadius() {
        val db = openRealDb()
        val (expectedHash, _) = loadFixture("00")
        // A random hash far from any real record should yield nothing at distance 0.
        val mostlyFlipped = expectedHash.inv()  // every bit flipped → distance 64 from fixture
        val hits = db.lookup(mostlyFlipped, maxHamming = 0, topN = 3)
        assertTrue("Expected empty result for an out-of-radius query, got $hits", hits.isEmpty())
    }

    @Test
    fun rejectsBadMagic() {
        val bogus = ByteArray(20).apply {
            // ASCII "XXXX" instead of "MTGP"
            this[0] = 'X'.code.toByte(); this[1] = 'X'.code.toByte()
            this[2] = 'X'.code.toByte(); this[3] = 'X'.code.toByte()
        }
        try {
            PhashDb.fromBytes(bogus)
            error("Should have thrown for bad magic bytes")
        } catch (e: IllegalStateException) {
            assertTrue("Wrong message: ${e.message}", e.message!!.contains("magic"))
        }
    }

    @Test
    fun rejectsTopNZero() {
        val db = openRealDb()
        try {
            db.lookup(0L, topN = 0)
            error("Should have thrown for topN=0")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun rejectsMaxHammingOutOfRange() {
        val db = openRealDb()
        try {
            db.lookup(0L, maxHamming = 65)
            error("Should have thrown for maxHamming=65")
        } catch (e: IllegalArgumentException) {
            // expected
        }
        try {
            db.lookup(0L, maxHamming = -1)
            error("Should have thrown for maxHamming=-1")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun fromBytesParsesAValidMinimalDb() {
        // Build a tiny in-memory DB with 2 records and read it back.
        val rec1 = encodeRecord(
            phash = 0x0101010101010101L,
            id = UUID.fromString("00000000-0000-0000-0000-000000000001"),
            mask = 0b0001,
            set = "tst",
            cn = "1",
        )
        val rec2 = encodeRecord(
            phash = 0x0202020202020202L,
            id = UUID.fromString("00000000-0000-0000-0000-000000000002"),
            mask = 0b0010,
            set = "tst",
            cn = "2",
        )
        val header = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN).apply {
            put('M'.code.toByte()); put('T'.code.toByte()); put('G'.code.toByte()); put('P'.code.toByte())
            putShort(1)  // version
            putInt(2)    // count
        }.array()
        val full = header + rec1 + rec2

        val db = PhashDb.fromBytes(full)
        assertEquals(2, db.recordCount)

        val hit = db.lookup(0x0101010101010101L, maxHamming = 0, topN = 1).single()
        assertEquals(UUID.fromString("00000000-0000-0000-0000-000000000001"), hit.scryfallId)
        assertEquals(0, hit.distance)
        assertEquals("tst", hit.setCode)
        assertEquals("1", hit.collectorNumber)
        assertTrue(hit.hasNonFoil)
        assertFalse(hit.hasFoil)
    }

    // --- helpers ---------------------------------------------------------------

    private fun openRealDb(): PhashDb {
        assumeTrue(
            "Skipping: real phash.bin not present at ${realDbPath.absolutePath} " +
                "(cwd=${File(".").absolutePath}). Generate via phash-db-generator or copy out/phash.bin.",
            realDbPath.exists(),
        )
        return PhashDb.fromFile(realDbPath.toPath())
    }

    /** Returns (expected_phash, scryfall_id) for fixture NN. */
    private fun loadFixture(name: String): Pair<Long, UUID> {
        val expectedHex = readResource("/phash-fixtures/$name/expected.txt").trim()
        val phash = java.lang.Long.parseUnsignedLong(expectedHex.removePrefix("0x"), 16)

        val meta = readResource("/phash-fixtures/$name/meta.txt")
        val idLine = meta.lineSequence().first { it.startsWith("scryfall_id:") }
        val id = UUID.fromString(idLine.substringAfter(":").trim())

        return phash to id
    }

    private fun readResource(path: String): String =
        String(javaClass.getResourceAsStream(path)!!.use { it.readBytes() })

    private fun encodeRecord(phash: Long, id: UUID, mask: Int, set: String, cn: String): ByteArray {
        val buf = ByteBuffer.allocate(41).order(ByteOrder.LITTLE_ENDIAN)
        buf.putLong(phash)
        // UUID stored as raw bytes (big-endian per RFC 4122) — temporarily swap order to write.
        val uuidBuf = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
        uuidBuf.putLong(id.mostSignificantBits).putLong(id.leastSignificantBits)
        buf.put(uuidBuf.array())
        buf.put(mask.toByte())
        buf.put(padField(set, 8))
        buf.put(padField(cn, 8))
        return buf.array()
    }

    private fun padField(s: String, len: Int): ByteArray {
        val bytes = s.toByteArray(Charsets.UTF_8)
        require(bytes.size <= len)
        return bytes + ByteArray(len - bytes.size)
    }
}
