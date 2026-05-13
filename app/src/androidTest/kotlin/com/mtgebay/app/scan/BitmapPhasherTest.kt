package com.mtgebay.app.scan

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader

/**
 * Instrumented test: drives [BitmapPhasher] on a real device through the OpenCV
 * native pipeline. Inputs are 32x32 grayscale PNGs derived from the same
 * fixtures used by the JVM-only [PhasherTest]; expected outputs are the same
 * golden pHashes.
 *
 * Because the input is already 32x32 grayscale, the `Imgproc.resize` step is a
 * no-op and the only thing being exercised is the Bitmap → Mat → grayscale
 * (no-op for R=G=B inputs) → uint8 readout plumbing. The pHash should therefore
 * match exactly; any non-zero Hamming distance points at a bug in the bridge,
 * not Lanczos discrepancy. Lanczos behavior is validated in a later phase
 * (2d/2e) once we feed full-resolution card images through the pipeline.
 */
@RunWith(AndroidJUnit4::class)
class BitmapPhasherTest {

    companion object {
        @BeforeClass
        @JvmStatic
        fun loadOpenCV() {
            // The app's Application class also does this, but the instrumentation
            // runner may invoke a test before the host Application's onCreate.
            // OpenCVLoader.initLocal() is idempotent.
            check(OpenCVLoader.initLocal()) { "OpenCV native library failed to load" }
        }
    }

    @Test fun fixture00() = checkFixture(0)
    @Test fun fixture01() = checkFixture(1)
    @Test fun fixture02() = checkFixture(2)
    @Test fun fixture03() = checkFixture(3)
    @Test fun fixture04() = checkFixture(4)
    @Test fun fixture05() = checkFixture(5)
    @Test fun fixture06() = checkFixture(6)
    @Test fun fixture07() = checkFixture(7)
    @Test fun fixture08() = checkFixture(8)
    @Test fun fixture09() = checkFixture(9)

    private fun checkFixture(index: Int) {
        val name = "%02d".format(index)
        val assets = InstrumentationRegistry.getInstrumentation().context.assets

        val bitmap = assets.open("phash-fixtures/$name/grayscale.png").use {
            BitmapFactory.decodeStream(it)
        }
        assertNotNull("Failed to decode fixture $name PNG", bitmap)
        assertEquals("Fixture $name should be 32 wide", 32, bitmap!!.width)
        assertEquals("Fixture $name should be 32 tall", 32, bitmap.height)

        val expectedHex = assets.open("phash-fixtures/$name/expected.txt")
            .bufferedReader()
            .use { it.readText() }
            .trim()
        val expected = java.lang.Long.parseUnsignedLong(expectedHex.removePrefix("0x"), 16)

        val actual = BitmapPhasher.phash(bitmap)
        val distance = (expected xor actual).countOneBits()
        val meta = runCatching {
            assets.open("phash-fixtures/$name/meta.txt").bufferedReader().use { it.readText() }
        }.getOrDefault("(no meta)")

        assertTrue(
            "Fixture $name BitmapPhasher mismatch.\n" +
                "  expected = 0x${expected.toULong().toString(16).padStart(16, '0')}\n" +
                "  actual   = 0x${actual.toULong().toString(16).padStart(16, '0')}\n" +
                "  hamming  = $distance (acceptable <= 2 for identity resize)\n" +
                "  meta:\n$meta",
            distance <= 2,
        )
    }
}
