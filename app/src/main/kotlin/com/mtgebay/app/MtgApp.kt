package com.mtgebay.app

import android.app.Application
import android.util.Log
import com.mtgebay.app.cleanup.CleanupScheduler
import org.opencv.android.OpenCVLoader

class MtgApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // Load the OpenCV native library packaged in the AAR. Required before any
        // org.opencv.* call (BitmapPhasher). initLocal() returns false if the .so
        // isn't on the device's ABI — we filter to arm64-v8a in build.gradle.kts
        // so this should never fail on a real device, but log loudly if it does.
        if (!OpenCVLoader.initLocal()) {
            Log.e(TAG, "OpenCV failed to load — image pipeline will not work")
        } else {
            Log.i(TAG, "OpenCV ${org.opencv.core.Core.VERSION} initialized")
        }

        // Schedule the periodic draft-cleanup job. KEEP policy means we don't
        // reset the schedule on every launch.
        CleanupScheduler.register(this)
    }

    companion object {
        private const val TAG = "MtgApp"
    }
}
