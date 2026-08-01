package com.exio.inkleaf.data

import java.io.File

/**
 * Deletes [start] and each empty ancestor up to (but excluding) [stop], mirroring the
 * directory layout after cache-entry removal. Shared by [ReaderCache] and [OnlinePageCache]
 * so their eviction paths keep the same semantics.
 */
internal fun deleteEmptyParents(start: File?, stop: File) {
    var current = start
    while (current != null && current != stop && current.list().isNullOrEmpty()) {
        val parent = current.parentFile
        current.delete()
        current = parent
    }
}
