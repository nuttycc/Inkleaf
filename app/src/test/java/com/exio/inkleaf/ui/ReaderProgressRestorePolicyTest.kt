package com.exio.inkleaf.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.cancel
import org.junit.Test
import org.junit.Assert.assertEquals

class ReaderProgressRestorePolicyTest {
    @Test
    fun `restored route prefers the durable page over its old route snapshot`() {
        assertEquals(
            50,
            ReaderProgressRestorePolicy.pageIndex(
                resumeFromPersistedPosition = true,
                explicitPageIndex = 3,
                persistedPageIndex = 50,
                fallbackPageIndex = 0,
            ),
        )
    }

    @Test
    fun `fresh explicit target remains authoritative over durable progress`() {
        assertEquals(
            3,
            ReaderProgressRestorePolicy.pageIndex(
                resumeFromPersistedPosition = false,
                explicitPageIndex = 3,
                persistedPageIndex = 50,
                fallbackPageIndex = 0,
            ),
        )
    }

    @Test
    fun `restored route follows the durable chapter when it is available`() {
        assertEquals(
            "chapter-2",
            ReaderProgressRestorePolicy.chapterId(
                resumeFromPersistedPosition = true,
                requestedChapterId = "chapter-1",
                persistedChapterId = "chapter-2",
                availableChapterIds = setOf("chapter-1", "chapter-2"),
            ),
        )
    }

    @Test
    fun `fresh route keeps its requested chapter`() {
        assertEquals(
            "chapter-1",
            ReaderProgressRestorePolicy.chapterId(
                resumeFromPersistedPosition = false,
                requestedChapterId = "chapter-1",
                persistedChapterId = "chapter-2",
                availableChapterIds = setOf("chapter-1", "chapter-2"),
            ),
        )
    }

    @Test
    fun `flushing pending progress writes only the latest value immediately`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val writes = mutableListOf<Int>()
        val queue =
            ReaderProgressWriteQueue<Int>(
                scope = scope,
                delayMillis = 60_000L,
            ) { value -> writes += value }

        queue.submit(3)
        queue.submit(50)
        queue.flush()

        assertEquals(listOf(50), writes)
        scope.cancel()
    }
}
