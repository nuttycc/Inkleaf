package com.exio.inkleaf.ui

import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import java.util.IdentityHashMap

/** Defers closing a volume until every asynchronous reader task has released it. */
internal class ReaderVolumeUseRegistry<T : Any>(
    private val close: (T) -> Unit,
) {
    private val useCounts = IdentityHashMap<T, Int>()
    private val pendingClose = java.util.Collections.newSetFromMap(IdentityHashMap<T, Boolean>())
    private val closed = WeakIdentitySet<T>()

    @Synchronized
    fun acquire(value: T): Boolean {
        if (value in pendingClose || value in closed) return false
        useCounts[value] = (useCounts[value] ?: 0) + 1
        return true
    }

    fun release(value: T) {
        val closeNow =
            synchronized(this) {
                val count = checkNotNull(useCounts[value]) { "Volume use was released without acquire" }
                if (count == 1) {
                    useCounts.remove(value)
                    if (pendingClose.remove(value)) {
                        closed.add(value)
                        true
                    } else {
                        false
                    }
                } else {
                    useCounts[value] = count - 1
                    false
                }
            }
        if (closeNow) close(value)
    }

    fun closeWhenUnused(value: T) {
        val closeNow =
            synchronized(this) {
                if (value in closed || value in pendingClose) {
                    false
                } else if (useCounts.containsKey(value)) {
                    pendingClose += value
                    false
                } else {
                    closed.add(value)
                    true
                }
            }
        if (closeNow) close(value)
    }
}

/** Blocks stale users without retaining every closed volume for the reader's lifetime. */
private class WeakIdentitySet<T : Any> {
    private val referenceQueue = ReferenceQueue<T>()
    private val references = HashSet<IdentityWeakReference<T>>()

    operator fun contains(value: T): Boolean {
        removeCollectedReferences()
        return IdentityWeakReference(value) in references
    }

    fun add(value: T) {
        removeCollectedReferences()
        references += IdentityWeakReference(value, referenceQueue)
    }

    private fun removeCollectedReferences() {
        while (true) {
            @Suppress("UNCHECKED_CAST")
            val reference = referenceQueue.poll() as? IdentityWeakReference<T> ?: return
            references.remove(reference)
        }
    }
}

private class IdentityWeakReference<T : Any>(
    value: T,
    referenceQueue: ReferenceQueue<T>? = null,
) : WeakReference<T>(value, referenceQueue) {
    private val identityHashCode = System.identityHashCode(value)

    override fun hashCode(): Int = identityHashCode

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is IdentityWeakReference<*>) return false
        val value = get() ?: return false
        return value === other.get()
    }
}
