package com.mtgebay.app.scan

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * UI-facing state for the scan flow. ML Kit Document Scanner handles the
 * capture + dewarp portion entirely; this VM only owns what happens once we
 * have a perspective-corrected card image.
 */
sealed interface ScanState {
    /** No scan in flight. Tap the button to launch ML Kit. */
    data object Idle : ScanState

    /** Loading the scanned bitmap, hashing it, and querying the DB. */
    data object Working : ScanState

    /** Pipeline ran but no record in the pHash DB came back within the Hamming radius. */
    data object NoCardDetected : ScanState

    /** One or more candidate matches, ordered by ascending Hamming distance. */
    data class Results(val hits: List<PhashHit>) : ScanState

    /** Unexpected error (decode failure, scanner launch failure, etc.). */
    data class Error(val message: String) : ScanState
}

/**
 * Orchestrates the lookup half of a scan. Input is a [Uri] handed back by ML
 * Kit's Document Scanner pointing at a JPEG of the dewarped card; output is a
 * [ScanState] transition that the UI renders.
 *
 * [PhashDb] is memory-mapped lazily out of the APK assets and reused across
 * scans.
 */
class ScanViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow<ScanState>(ScanState.Idle)
    val state: StateFlow<ScanState> = _state.asStateFlow()

    private val phashDb: PhashDb by lazy { PhashDb.fromAssets(getApplication()) }

    /**
     * Hand a freshly-scanned image URI (from ML Kit) to the pipeline.
     */
    fun onDocumentScanned(uri: Uri) {
        viewModelScope.launch {
            _state.value = ScanState.Working
            try {
                val next = withContext(Dispatchers.IO) { runPipeline(uri) }
                _state.value = next
            } catch (t: Throwable) {
                Log.e(TAG, "scan pipeline failed", t)
                _state.value = ScanState.Error(t.message ?: "Unknown error")
            }
        }
    }

    /** Surface a non-pipeline error (e.g. scanner failed to launch). */
    fun onScanError(message: String) {
        Log.w(TAG, "scan error: $message")
        _state.value = ScanState.Error(message)
    }

    fun reset() {
        _state.value = ScanState.Idle
    }

    private fun runPipeline(uri: Uri): ScanState {
        val app = getApplication<Application>()
        val bitmap = app.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream)
        } ?: return ScanState.Error("Failed to read scanned image at $uri")

        return try {
            // Hash two variants of the scanned card to cover both DB entry styles:
            //   - whole-card  → matches full_art / borderless DB entries
            //   - art-region  → matches normal-frame DB entries (see ART_CROP in generate.py)
            val wholeHash = BitmapPhasher.phash(bitmap)
            val cropped = cropToArtRegion(bitmap)
            val artHash = try {
                BitmapPhasher.phash(cropped)
            } finally {
                if (!cropped.isRecycled && cropped !== bitmap) cropped.recycle()
            }

            Log.i(
                TAG,
                "scanned ${bitmap.width}x${bitmap.height} " +
                    "wholeHash=0x${wholeHash.toULong().toString(16).padStart(16, '0')} " +
                    "artHash=0x${artHash.toULong().toString(16).padStart(16, '0')}",
            )

            val wholeNearest = phashDb.lookup(wholeHash, maxHamming = 64, topN = 5)
            val artNearest = phashDb.lookup(artHash, maxHamming = 64, topN = 5)
            Log.i(TAG, "nearest with whole-card: " + wholeNearest.joinToString { "d=${it.distance} ${it.setCode.uppercase()}#${it.collectorNumber}" })
            Log.i(TAG, "nearest with art-crop:   " + artNearest.joinToString { "d=${it.distance} ${it.setCode.uppercase()}#${it.collectorNumber}" })

            // Use whichever variant got closer to the DB; that's the right hashing
            // strategy for this particular card's frame style.
            val bestWhole = wholeNearest.firstOrNull()
            val bestArt = artNearest.firstOrNull()
            val winnerList = when {
                bestWhole == null && bestArt == null -> emptyList()
                bestWhole == null -> artNearest
                bestArt == null -> wholeNearest
                bestArt.distance <= bestWhole.distance -> artNearest
                else -> wholeNearest
            }

            val withinThreshold = winnerList.filter { it.distance <= PhashDb.DEFAULT_MAX_HAMMING }
            if (withinThreshold.isNotEmpty()) {
                ScanState.Results(withinThreshold.take(3))
            } else if (winnerList.isNotEmpty()) {
                // Show closest 3 anyway so the user can see how far off we were.
                ScanState.Results(winnerList.take(3))
            } else {
                ScanState.NoCardDetected
            }
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    /**
     * Mirror generate.py's `ART_CROP` rectangle (0.075, 0.110, 0.925, 0.485). For a
     * standard MTG frame, this isolates the art region in the upper portion of the
     * card — the same region the offline pHash DB was built from.
     */
    private fun cropToArtRegion(bmp: Bitmap): Bitmap {
        val w = bmp.width
        val h = bmp.height
        val left = (w * 0.075).toInt()
        val top = (h * 0.110).toInt()
        val right = (w * 0.925).toInt()
        val bottom = (h * 0.485).toInt()
        if (right <= left || bottom <= top) return bmp
        return Bitmap.createBitmap(bmp, left, top, right - left, bottom - top)
    }

    private companion object {
        const val TAG = "ScanViewModel"
    }
}
