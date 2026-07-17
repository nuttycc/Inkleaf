package com.exio.inkleaf.data.enhancement.cache

import java.util.concurrent.atomic.AtomicInteger

/** Prevents bulk inference from competing with a visible reader. */
object EnhancementReaderActivity {
    private val visibleReaderCount = AtomicInteger(0)

    val isReaderVisible: Boolean get() = visibleReaderCount.get() > 0

    fun readerEntered() {
        visibleReaderCount.incrementAndGet()
    }

    fun readerExited() {
        visibleReaderCount.updateAndGet { count -> (count - 1).coerceAtLeast(0) }
    }
}
