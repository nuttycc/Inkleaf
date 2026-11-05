package com.exio.inkleaf.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
    fun `only restored routes refresh metadata for the durable chapter`() {
        assertTrue(
            ReaderProgressRestorePolicy.shouldRestoreChapterMetadata(
                resumeFromPersistedPosition = true,
                resolvedChapterId = "chapter-1",
                persistedChapterId = "chapter-1",
            )
        )
        assertFalse(
            ReaderProgressRestorePolicy.shouldRestoreChapterMetadata(
                resumeFromPersistedPosition = false,
                resolvedChapterId = "chapter-1",
                persistedChapterId = "chapter-1",
            )
        )
        assertFalse(
            ReaderProgressRestorePolicy.shouldRestoreChapterMetadata(
                resumeFromPersistedPosition = true,
                resolvedChapterId = "chapter-1",
                persistedChapterId = "chapter-2",
            )
        )
    }
}
