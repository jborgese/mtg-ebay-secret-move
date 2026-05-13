package com.mtgebay.app.cleanup

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker.Result
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke test that the worker can be built + invoked end-to-end without
 * crashing. The actual pruning logic is covered by DraftRepositoryTest;
 * here we just verify the WorkManager wiring + Room lookup work on device.
 */
@RunWith(AndroidJUnit4::class)
class DraftCleanupWorkerTest {

    @Test
    fun doWork_returnsSuccess() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val worker = TestListenableWorkerBuilder<DraftCleanupWorker>(context).build()
        val result = worker.doWork()
        assertEquals(Result.success(), result)
    }
}
