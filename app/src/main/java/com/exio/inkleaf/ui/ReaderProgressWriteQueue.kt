package com.exio.inkleaf.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Coalesces fast page changes and can durably drain the latest one at a lifecycle boundary. */
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
        pending?.let { latest ->
            pending = null
            writeLatest(latest)
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
    }

    private suspend fun writeLatest(value: T) {
        withContext(NonCancellable) { write(value) }
    }
}
