package com.exio.inkleaf.data.enhancement

import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import java.util.HashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Keeps a caller-owned resource alive while a coordinator-owned producer still uses it.
 *
 * The registry is identity-based because resource instances, rather than their value equality,
 * define the lifetime boundary. Once closing starts, a weak marker rejects late leases without
 * retaining every previously opened volume for the rest of the process.
 */
internal class EnhancementResourceLeaseRegistry<T : Any> {
    private class IdentityWeakReference<T : Any>(
        referent: T,
        queue: ReferenceQueue<T>? = null,
    ) : WeakReference<T>(referent, queue) {
        private val identityHashCode = System.identityHashCode(referent)

        override fun hashCode(): Int = identityHashCode

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is IdentityWeakReference<*>) return false
            val referent = get() ?: return false
            return referent === other.get()
        }
    }

    private class Entry<T : Any>(val key: IdentityWeakReference<T>) {
        var activeLeases: Int = 0
        var closing: Boolean = false
        var closeStarted: Boolean = false
        val idle = CompletableDeferred<Unit>()
        val closed = CompletableDeferred<Unit>()
    }

    private val lock = Any()
    private val referenceQueue = ReferenceQueue<T>()
    private val entries = HashMap<IdentityWeakReference<T>, Entry<T>>()

    fun acquire(resource: T): AutoCloseable {
        val entry = synchronized(lock) {
            removeCollectedEntries()
            val current = entries[IdentityWeakReference(resource)]
            if (current?.closing == true) {
                throw CancellationException("Enhancement resource is closing")
            }
            (current ?: createEntry(resource)).also {
                it.activeLeases += 1
            }
        }
        return Lease(resource, entry)
    }

    /**
     * Marks [resource] closed to new users, waits for producer leases, then invokes [close].
     * Concurrent close requests share one close operation.
     */
    suspend fun closeAfterIdle(resource: T, close: (T) -> Unit): Unit =
        withContext(NonCancellable) {
            val (entry, performClose) = synchronized(lock) {
                removeCollectedEntries()
                val current = entries[IdentityWeakReference(resource)] ?: createEntry(resource)
                val shouldClose = !current.closeStarted
                current.closing = true
                current.closeStarted = true
                if (current.activeLeases == 0) current.idle.complete(Unit)
                current to shouldClose
            }
            if (!performClose) {
                entry.closed.await()
                return@withContext
            }

            try {
                entry.idle.await()
                close(resource)
                entry.closed.complete(Unit)
            } catch (error: Throwable) {
                entry.closed.completeExceptionally(error)
                throw error
            }
            Unit
        }

    /** Test-only state observation that avoids timing-dependent lease assertions. */
    internal fun isClosing(resource: T): Boolean = synchronized(lock) {
        removeCollectedEntries()
        entries[IdentityWeakReference(resource)]?.closing == true
    }

    private fun createEntry(resource: T): Entry<T> {
        val key = IdentityWeakReference(resource, referenceQueue)
        return Entry(key).also { entries[key] = it }
    }

    private fun removeCollectedEntries() {
        while (true) {
            val collected = referenceQueue.poll() ?: return
            entries.remove(collected)
        }
    }

    private fun release(entry: Entry<T>) {
        synchronized(lock) {
            if (entry.activeLeases <= 0) return
            entry.activeLeases -= 1
            if (entry.activeLeases == 0) {
                entry.idle.complete(Unit)
                if (!entry.closing) entries.remove(entry.key)
            }
        }
    }

    private inner class Lease(
        resource: T,
        private val entry: Entry<T>,
    ) : AutoCloseable {
        @Suppress("unused")
        private var retainedResource: T? = resource
        private var released = false

        override fun close() {
            synchronized(lock) {
                if (released) return
                released = true
                retainedResource = null
            }
            release(entry)
        }
    }
}
