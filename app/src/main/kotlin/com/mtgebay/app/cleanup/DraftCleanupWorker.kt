package com.mtgebay.app.cleanup

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.mtgebay.app.data.DraftRepository
import com.mtgebay.app.data.db.AppDatabase
import java.io.File

/**
 * Periodic worker that drops drafts whose `updatedAt` is more than 30 days
 * old, along with their photo directories. Spec: drafts go stale fast for an
 * auction-pricing app — month-old draft prices aren't usable anyway.
 *
 * Scheduling is owned by [CleanupScheduler] (called from `MtgApp.onCreate`).
 */
class DraftCleanupWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext
        val repository = DraftRepository(
            dao = AppDatabase.get(app).draftDao(),
            photosDir = File(app.filesDir, "photos"),
        )
        return try {
            val deleted = repository.pruneStale()
            Log.i(TAG, "Pruned $deleted stale drafts")
            Result.success()
        } catch (t: Throwable) {
            Log.e(TAG, "Pruning failed; will retry", t)
            Result.retry()
        }
    }

    companion object {
        const val UNIQUE_NAME = "draft-cleanup"
        private const val TAG = "DraftCleanupWorker"
    }
}
