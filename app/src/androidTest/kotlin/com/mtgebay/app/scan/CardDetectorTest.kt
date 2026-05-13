package com.mtgebay.app.scan

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader

/**
 * Instrumented validation of [CardDetector] against real Scryfall card images
 * (the same files used to seed the offline pHash generator). Each fixture is
 * a full-card JPG with at most a few pixels of background; the detector should
 * find the card-edge quadrilateral and warp it to 488x680.
 *
 * This test does **not** assert that the resulting pHash matches the offline
 * reference — the offline hash is computed on the cropped art region for
 * normal-frame cards, while [CardDetector] returns the whole card. That
 * alignment is addressed in Phase 2e/2f.
 */
@RunWith(AndroidJUnit4::class)
class CardDetectorTest {

    companion object {
        @BeforeClass
        @JvmStatic
        fun loadOpenCV() {
            check(OpenCVLoader.initLocal()) { "OpenCV native library failed to load" }
        }
    }

    @Test fun detects_warRoom_fixture00() = checkFixture("00")
    @Test fun detects_mountainSld_fixture02() = checkFixture("02")

    @Test
    fun returnsNullForSolidColorBitmap() {
        val bmp = Bitmap.createBitmap(800, 1100, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.GRAY)
        try {
            assertNull("Solid bitmap has no quadrilateral", CardDetector.detect(bmp))
        } finally {
            bmp.recycle()
        }
    }

    @Test
    fun returnsNullForTinyInput() {
        val bmp = Bitmap.createBitmap(50, 50, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        try {
            assertNull("Tiny bitmap is too small to host a card", CardDetector.detect(bmp))
        } finally {
            bmp.recycle()
        }
    }

    private fun checkFixture(name: String) {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val bitmap = assets.open("card-detector/$name/source.jpg").use {
            BitmapFactory.decodeStream(it)
        }
        assertNotNull("Failed to decode card-detector/$name/source.jpg", bitmap)

        try {
            val warped = CardDetector.detect(bitmap!!)
            assertNotNull(
                "CardDetector returned null for fixture $name (${bitmap.width}x${bitmap.height})",
                warped,
            )
            try {
                assertEquals(
                    "Warped output should be ${CardDetector.OUT_WIDTH} wide",
                    CardDetector.OUT_WIDTH,
                    warped!!.width,
                )
                assertEquals(
                    "Warped output should be ${CardDetector.OUT_HEIGHT} tall",
                    CardDetector.OUT_HEIGHT,
                    warped.height,
                )

                // Sanity: the warped image should have a healthy dynamic range — a card
                // is never uniformly black or white. Sample a 10x10 grid and check the
                // pixel-luminance spread.
                val pixels = IntArray(100)
                for (yi in 0 until 10) {
                    for (xi in 0 until 10) {
                        val x = (xi * (warped.width - 1)) / 9
                        val y = (yi * (warped.height - 1)) / 9
                        pixels[yi * 10 + xi] = warped.getPixel(x, y)
                    }
                }
                val luma = pixels.map { p ->
                    (Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114) / 1000
                }
                val range = (luma.max() - luma.min())
                assertTrue(
                    "Warped output luma range is suspiciously small ($range) — detection likely failed silently",
                    range > 30,
                )
            } finally {
                warped?.recycle()
            }
        } finally {
            bitmap?.recycle()
        }
    }
}
