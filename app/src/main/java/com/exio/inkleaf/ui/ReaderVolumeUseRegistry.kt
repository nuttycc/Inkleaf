package com.exio.inkleaf.ui

import java.util.IdentityHashMap

/** Defers closing a volume until every asynchronous reader task has released it. */
internal class ReaderVolumeUseRegistry<T : Any>(
    private val close: (T) -> Unit,
) {
    private val useCounts = IdentityHashMap<T, Int>()
    private val pendingClose = java.util.Collections.newSetFromMap(IdentityHashMap<T, Boolean>())
    private val closed = java.util.Collections.newSetFromMap(IdentityHashMap<T, Boolean>())

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
                        closed += value
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
                    closed += value
                    true
                }
            }
        if (closeNow) close(value)
    }
}
