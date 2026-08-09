package com.exio.inkleaf.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Coalesces fast page changes and can durably drain the latest one at a lifecycle boundary.
 *
 * Submissions received during a flush stay pending for the same drain instead of starting a
 * competing writer. Closing rejects late UI callbacks so they cannot overtake the final write.
 *
 * Call its state-mutating methods from one serialized coroutine context.
 */
internal class ReaderProgressWriteQueue<T>(
    private val scope: CoroutineScope,
    private val delayMillis: Long,
    private val write: suspend (T) -> Unit,
) {
    private var pending: T? = null
    private var job: Job? = null
    private var flushing = false
    private var closed = false
    private val flushMutex = Mutex()

    fun submit(value: T) {
        if (closed) return
        pending = value
        if (flushing || job?.isActive == true) return
        startWorker()
    }

    fun flushBestEffort() {
        if (closed) return
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                flush()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Progress persistence remains best-effort at lifecycle boundaries.
            }
        }
    }

    suspend fun flush() =
        flushMutex.withLock {
            if (closed) return@withLock
            flushing = true
            try {
                while (!closed) {
                    if (job?.isActive != true) {
                        if (pending == null) break
                        startWorker()
                    }
                    val activeJob = job ?: break
                    activeJob.cancelAndJoin()
                    if (job === activeJob) job = null
                    if (pending == null) break
                }
            } finally {
                flushing = false
                if (!closed && pending != null && job?.isActive != true) startWorker()
            }
        }

    /** Rejects future submissions and returns the worker so a final write can await it. */
    fun close(): Job? {
        closed = true
        pending = null
        val cancelledJob = job
        job = null
        cancelledJob?.cancel()
        return cancelledJob
    }

    private fun startWorker() {
        job =
            // Enter try/finally before submit returns so an immediate flush still drains pending.
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    while (true) {
                        delay(delayMillis)
                        val latest = pending ?: break
                        pending = null
                        writeLatest(latest)
                    }
                } finally {
                    pending?.let { latest ->
                        pending = null
                        writeLatest(latest)
                    }
                }
            }
    }

    private suspend fun writeLatest(value: T) {
        withContext(NonCancellable) { write(value) }
    }
}
