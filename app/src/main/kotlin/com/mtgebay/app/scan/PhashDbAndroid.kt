package com.mtgebay.app.scan

import android.content.Context
import java.io.FileInputStream
import java.nio.channels.FileChannel

/**
 * Memory-map `phash.bin` out of the APK assets section into a [PhashDb].
 *
 * Requires `noCompress.add("bin")` in `app/build.gradle.kts` so that the asset
 * is stored uncompressed inside the APK. `AssetManager.openFd()` then returns
 * an [android.content.res.AssetFileDescriptor] with a startOffset / length into
 * the APK file itself, which we map directly — no copy, no in-memory load.
 */
fun PhashDb.Companion.fromAssets(
    context: Context,
    assetName: String = "phash.bin",
): PhashDb {
    return context.assets.openFd(assetName).use { afd ->
        FileInputStream(afd.fileDescriptor).use { stream ->
            val buffer = stream.channel.map(
                FileChannel.MapMode.READ_ONLY,
                afd.startOffset,
                afd.declaredLength,
            )
            PhashDb.fromBuffer(buffer)
        }
    }
}
