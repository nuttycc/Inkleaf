package com.exio.inkleaf.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ReaderProgressWriteQueueTest {
    @Test
    fun `submissions inside the delay window coalesce into one write`() = runBlocking {
        val writes = mutableListOf<Int>()
        val queue =
            ReaderProgressWriteQueue<Int>(
                scope = this,
                delayMillis = 1L,
            ) { value ->
                writes += value
            }

        queue.submit(1)
        queue.submit(2)
        queue.submit(3)
        delay(20)

        assertEquals(listOf(3), writes)
        queue.close()
        Unit
    }

    @Test
    fun `flushing pending progress writes only the latest value immediately`() = runBlocking {
        val writes = mutableListOf<Int>()
        val queue =
            ReaderProgressWriteQueue<Int>(
                scope = this,
                delayMillis = 60_000L,
            ) { value ->
                writes += value
            }

        queue.submit(3)
        queue.submit(50)
        queue.flush()

        assertEquals(listOf(50), writes)
        queue.close()
        Unit
    }

    @Test
    fun `flush serializes submissions made during an in-flight write`() = runBlocking {
        val firstWriteStarted = CompletableDeferred<Unit>()
        val releaseFirstWrite = CompletableDeferred<Unit>()
        val secondWriteStarted = CompletableDeferred<Unit>()
        val writes = mutableListOf<Int>()
        val queue =
            ReaderProgressWriteQueue<Int>(
                scope = this,
                delayMillis = 0L,
            ) { value ->
                when (value) {
                    1 -> {
                        firstWriteStarted.complete(Unit)
                        releaseFirstWrite.await()
                    }
                    2 -> secondWriteStarted.complete(Unit)
                }
                writes += value
            }

        queue.submit(1)
        firstWriteStarted.await()
        val flushJob = launch(start = CoroutineStart.UNDISPATCHED) { queue.flush() }
        queue.submit(2)
        yield()

        try {
            assertFalse(secondWriteStarted.isCompleted)
        } finally {
            releaseFirstWrite.complete(Unit)
        }
        flushJob.join()

        assertEquals(listOf(1, 2), writes)
        queue.close()
        Unit
    }

    @Test
    fun `close rejects late submissions and keeps only work that already started`() = runBlocking {
        val writeStarted = CompletableDeferred<Unit>()
        val releaseWrite = CompletableDeferred<Unit>()
        val writes = mutableListOf<Int>()
        val queue =
            ReaderProgressWriteQueue<Int>(
                scope = this,
                delayMillis = 0L,
            ) { value ->
                if (value == 1) {
                    writeStarted.complete(Unit)
                    releaseWrite.await()
                }
                writes += value
            }

        queue.submit(1)
        writeStarted.await()
        queue.submit(2)
        val queuedJob = queue.close()
        queue.submit(4)
        val finalWrite =
            launch(start = CoroutineStart.UNDISPATCHED) {
                queuedJob?.join()
                writes += 3
            }

        try {
            assertFalse(finalWrite.isCompleted)
        } finally {
            releaseWrite.complete(Unit)
        }
        finalWrite.join()

        assertEquals(listOf(1, 3), writes)
    }
}
