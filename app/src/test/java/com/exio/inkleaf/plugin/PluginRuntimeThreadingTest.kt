package com.exio.inkleaf.plugin

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginRuntimeThreadingTest {
    @Test
    fun runtimeCloseRunsOffCallerThread() = runBlocking {
        val callerThread = Thread.currentThread()
        var closeThread: Thread? = null

        closeRuntimeOffMain { closeThread = Thread.currentThread() }

        assertNotNull(closeThread)
        assertNotSame(callerThread, closeThread)
    }

    @Test
    fun runtimeCloseCompletesAfterCallerCancellation() = runBlocking {
        var closed = false

        val job =
            launch(start = CoroutineStart.UNDISPATCHED) {
                cancel()
                closeRuntimeOffMain { closed = true }
            }
        job.join()

        assertTrue(closed)
    }
}
