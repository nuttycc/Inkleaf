package com.exio.inkleaf.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Coalesces fast page changes and can durably drain the latest one at a lifecycle boundary.
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

    val hasPending: Boolean
        get() = pending != null

    fun submit(value: T) {
        pending = value
        if (job?.isActive == true) return
        job =
            scope.launch {
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

    suspend fun flush() {
        job?.cancelAndJoin()
        job = null
        // Drain any value left outside the worker so the lifecycle boundary remains durable.
        pending?.let { latest ->
            pending = null
            writeLatest(latest)
        }
    }

    /** Discards queued work and returns the worker so a replacement write can await it. */
    fun cancel(): Job? {
        pending = null
        val cancelledJob = job
        job = null
        cancelledJob?.cancel()
        return cancelledJob
    }

    private suspend fun writeLatest(value: T) {
        withContext(NonCancellable) { write(value) }
    }
}
