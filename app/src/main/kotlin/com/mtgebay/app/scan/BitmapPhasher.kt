package com.mtgebay.app.scan

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * Android wrapper that turns a [Bitmap] into the 32x32 grayscale [DoubleArray]
 * required by [Phasher.phash]. Bridges the OpenCV image pipeline (Bitmap → Mat
 * → grayscale → Lanczos resize → uint8 readout) to the pure-JVM hash core.
 *
 * Requires OpenCV's native library to be loaded — see `MtgApp.onCreate()`.
 *
 * Pipeline (matches the offline `generate.py` end-to-end where it can):
 *  1. `Utils.bitmapToMat(bitmap)` → RGBA `Mat`.
 *  2. `Imgproc.cvtColor(RGBA → GRAY)` using the standard ITU-R BT.601 weights,
 *     identical to PIL's `Image.convert('L')`.
 *  3. `Imgproc.resize(..., INTER_LANCZOS4)` to 32x32.
 *  4. Read out 1024 uint8 pixels, cast to `Double`, feed [Phasher.phash].
 *
 * Note: OpenCV's `INTER_LANCZOS4` uses an 8-tap kernel; PIL's `LANCZOS` uses a
 * 6-tap kernel. The two are visually equivalent but produce slightly different
 * float values, so the resulting pHash may differ from the offline reference by
 * a handful of bits. That tolerance is what [PhashDb.DEFAULT_MAX_HAMMING] is for.
 */
object BitmapPhasher {

    private const val SIZE = 32

    /**
     * Compute the 64-bit pHash of a [Bitmap]. The bitmap may be any size; it is
     * not modified. The caller is responsible for any prior crop / perspective
     * correction (Phase 2d).
     */
    fun phash(bitmap: Bitmap): Long {
        val grayscale = bitmapToGrayscale32x32(bitmap)
        return Phasher.phash(grayscale)
    }

    /**
     * Bitmap → 32x32 grayscale `DoubleArray` (row-major, values 0.0..255.0).
     * Exposed for tests that want to verify the intermediate matrix or supply
     * a pre-resized fixture image.
     */
    fun bitmapToGrayscale32x32(bitmap: Bitmap): DoubleArray {
        val rgba = Mat()
        try {
            Utils.bitmapToMat(bitmap, rgba)

            val gray = Mat()
            try {
                Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)

                val resized = Mat()
                try {
                    Imgproc.resize(
                        gray,
                        resized,
                        Size(SIZE.toDouble(), SIZE.toDouble()),
                        0.0,
                        0.0,
                        Imgproc.INTER_LANCZOS4,
                    )

                    val bytes = ByteArray(SIZE * SIZE)
                    resized.get(0, 0, bytes)
                    return DoubleArray(SIZE * SIZE) { i ->
                        (bytes[i].toInt() and 0xFF).toDouble()
                    }
                } finally {
                    resized.release()
                }
            } finally {
                gray.release()
            }
        } finally {
            rgba.release()
        }
    }
}
