package com.mtgebay.app.scan

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * Detect a single Magic card in a camera frame and return its perspective-corrected,
 * portrait-oriented view at the canonical MTG card aspect ratio (488x680 ≈ 2.5:3.5).
 *
 * Pipeline (all OpenCV):
 *   1. Convert to grayscale and Gaussian-blur to denoise.
 *   2. Canny edge detection.
 *   3. `findContours` (external only) — produces candidate object outlines.
 *   4. Filter by area (≥ [MIN_AREA_RATIO] of frame) and by quadrilateral shape
 *      via `approxPolyDP`.
 *   5. Pick the largest 4-sided candidate.
 *   6. Order its corners (TL, TR, BR, BL) by image-space sum / difference.
 *   7. `getPerspectiveTransform` → `warpPerspective` to [OUT_WIDTH] × [OUT_HEIGHT].
 *
 * Returns `null` when no card-like quadrilateral is present. Output Mat is owned
 * by the caller and must be `release()`d.
 *
 * Requires OpenCV's native library to be loaded — see `MtgApp.onCreate()`.
 *
 * **Known limitation in v1:** corner ordering uses image-space TL/TR/BR/BL,
 * which always produces a portrait-oriented warp **assuming the camera is held
 * in portrait orientation**. Landscape captures or 180°-flipped cards produce
 * rotated outputs; the caller can hash multiple rotations if needed.
 */
object CardDetector {

    /** Canonical MTG card dimensions in normalized pixels (2.5"x3.5" → 5:7). */
    const val OUT_WIDTH = 488
    const val OUT_HEIGHT = 680

    /** A detected quadrilateral must cover at least this fraction of the frame. */
    private const val MIN_AREA_RATIO = 0.05

    /** Canny edge thresholds — empirical defaults; tuned per camera in Phase 2f. */
    private const val CANNY_LOW = 50.0
    private const val CANNY_HIGH = 150.0

    /** `approxPolyDP` epsilon as a fraction of the contour perimeter. */
    private const val POLY_EPSILON_FRAC = 0.02

    /**
     * Detect and warp a card in [frame]. Returns the 488x680 warped Mat or null.
     * The input is not modified; the caller owns the returned Mat.
     */
    fun detect(frame: Mat): Mat? {
        if (frame.empty() || frame.cols() < 100 || frame.rows() < 100) return null

        val gray = Mat()
        val blurred = Mat()
        val edges = Mat()
        val hierarchy = Mat()
        val contours = ArrayList<MatOfPoint>()

        try {
            // Handle 1/3/4-channel inputs. cvtColor with RGBA2GRAY also works for RGB and BGR
            // inputs that have been wrapped in 4-channel Mats; single-channel input we use as-is.
            if (frame.channels() == 1) {
                frame.copyTo(gray)
            } else {
                Imgproc.cvtColor(frame, gray, Imgproc.COLOR_RGBA2GRAY)
            }
            Imgproc.GaussianBlur(gray, blurred, Size(5.0, 5.0), 0.0)
            Imgproc.Canny(blurred, edges, CANNY_LOW, CANNY_HIGH)

            Imgproc.findContours(
                edges,
                contours,
                hierarchy,
                Imgproc.RETR_EXTERNAL,
                Imgproc.CHAIN_APPROX_SIMPLE,
            )

            val minArea = frame.cols().toDouble() * frame.rows() * MIN_AREA_RATIO
            val bestQuad = findLargestQuadrilateral(contours, minArea) ?: return null
            try {
                return warpToCard(frame, bestQuad)
            } finally {
                bestQuad.release()
            }
        } finally {
            gray.release()
            blurred.release()
            edges.release()
            hierarchy.release()
            contours.forEach { it.release() }
        }
    }

    /**
     * Bitmap convenience wrapper. Caller owns the returned [Bitmap].
     * Returns null when no card is detected.
     */
    fun detect(bitmap: Bitmap): Bitmap? {
        val frame = Mat()
        try {
            Utils.bitmapToMat(bitmap, frame)
            val warped = detect(frame) ?: return null
            try {
                val out = Bitmap.createBitmap(OUT_WIDTH, OUT_HEIGHT, Bitmap.Config.ARGB_8888)
                Utils.matToBitmap(warped, out)
                return out
            } finally {
                warped.release()
            }
        } finally {
            frame.release()
        }
    }

    /**
     * Walks [contours] and returns the largest contour whose [approxPolyDP] yields
     * a 4-vertex polygon with at least [minArea] pixels of contour area.
     * Returns null if no candidate qualifies. Caller owns the returned [MatOfPoint2f].
     */
    private fun findLargestQuadrilateral(
        contours: List<MatOfPoint>,
        minArea: Double,
    ): MatOfPoint2f? {
        var best: MatOfPoint2f? = null
        var bestArea = 0.0

        for (contour in contours) {
            val area = Imgproc.contourArea(contour)
            if (area < minArea) continue

            val contour2f = MatOfPoint2f(*contour.toArray())
            val approx = MatOfPoint2f()
            try {
                val perimeter = Imgproc.arcLength(contour2f, true)
                Imgproc.approxPolyDP(contour2f, approx, POLY_EPSILON_FRAC * perimeter, true)

                if (approx.total() == 4L && area > bestArea) {
                    best?.release()
                    // approx is now adopted as `best`; create a fresh approx for the next iteration.
                    best = approx
                    bestArea = area
                    continue
                }
            } finally {
                contour2f.release()
            }
            // Either it wasn't a quad or wasn't bigger than the current best.
            approx.release()
        }
        return best
    }

    /** Builds the perspective-warped 488x680 Mat for the given 4-corner quadrilateral. */
    private fun warpToCard(frame: Mat, quad: MatOfPoint2f): Mat {
        val orderedSrc = orderCorners(quad.toArray())
        val src = MatOfPoint2f(*orderedSrc)
        val dst = MatOfPoint2f(
            Point(0.0, 0.0),
            Point((OUT_WIDTH - 1).toDouble(), 0.0),
            Point((OUT_WIDTH - 1).toDouble(), (OUT_HEIGHT - 1).toDouble()),
            Point(0.0, (OUT_HEIGHT - 1).toDouble()),
        )
        val transform = Imgproc.getPerspectiveTransform(src, dst)
        val warped = Mat()
        try {
            Imgproc.warpPerspective(
                frame,
                warped,
                transform,
                Size(OUT_WIDTH.toDouble(), OUT_HEIGHT.toDouble()),
            )
        } finally {
            src.release()
            dst.release()
            transform.release()
        }
        return warped
    }

    /**
     * Order 4 corners as [TL, TR, BR, BL] in image space. Uses the classic
     * sum/difference trick:
     *   - top-left has the smallest x + y
     *   - bottom-right has the largest x + y
     *   - top-right has the largest x - y
     *   - bottom-left has the smallest x - y
     */
    private fun orderCorners(corners: Array<Point>): Array<Point> {
        require(corners.size == 4) { "Expected 4 corners, got ${corners.size}" }
        val tl = corners.minByOrNull { it.x + it.y }!!
        val br = corners.maxByOrNull { it.x + it.y }!!
        val tr = corners.maxByOrNull { it.x - it.y }!!
        val bl = corners.minByOrNull { it.x - it.y }!!
        return arrayOf(tl, tr, br, bl)
    }
}
