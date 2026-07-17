package com.exio.inkleaf.data.enhancement

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Shares one producer among concurrent requests for the same key. */
internal class InFlightRequestRegistry<K, V> {
    private data class Request<V>(
        val result: CompletableDeferred<V>,
        val isOwner: Boolean,
    )

    private val mutex = Mutex()
    private val requests = mutableMapOf<K, CompletableDeferred<V>>()

    suspend fun run(key: K, producer: suspend () -> V): V {
        val request = mutex.withLock {
            requests[key]?.let { existing ->
                Request(existing, isOwner = false)
            } ?: CompletableDeferred<V>().let { created ->
                requests[key] = created
                Request(created, isOwner = true)
            }
        }
        if (!request.isOwner) return request.result.await()

        try {
            val value = producer()
            request.result.complete(value)
            return value
        } catch (error: Throwable) {
            request.result.completeExceptionally(error)
            throw error
        } finally {
            withContext(NonCancellable) {
                mutex.withLock {
                    if (requests[key] === request.result) requests.remove(key)
                }
            }
        }
    }
}
