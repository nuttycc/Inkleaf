package com.exio.inkleaf.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ReaderContentIdentityTest {
    @Test
    fun `local content identity uses only the stable file key`() {
        assertEquals(LocalContentIdentity("stat:1:2"), LocalContentIdentity("stat:1:2"))
        assertNotEquals(LocalContentIdentity("stat:1:2"), LocalContentIdentity("stat:1:3"))
    }

    @Test
    fun `online content and chapter identities are source scoped`() {
        val content = OnlineContentIdentity("io.example.source", "comic-1")

        assertEquals(content, OnlineContentIdentity("io.example.source", "comic-1"))
        assertNotEquals(content, OnlineContentIdentity("io.example.other", "comic-1"))
        assertNotEquals(
            OnlineChapterIdentity(content, "chapter-1"),
            OnlineChapterIdentity(content, "chapter-2"),
        )
    }

    @Test
    fun `source page id remains stable across index and revision changes`() {
        val chapter = chapter()

        val first = OnlinePageIdentity.create(chapter, "page-7", 6, "revision-1")
        val moved = OnlinePageIdentity.create(chapter, "page-7", 18, "revision-2")

        assertEquals(first, moved)
    }

    @Test
    fun `page index fallback is bound to the chapter revision`() {
        val chapter = chapter()

        val first = OnlinePageIdentity.create(chapter, null, 6, "revision-1")
        val changedRevision = OnlinePageIdentity.create(chapter, null, 6, "revision-2")
        val changedIndex = OnlinePageIdentity.create(chapter, null, 7, "revision-1")

        assertNotEquals(first, changedRevision)
        assertNotEquals(first, changedIndex)
        assertThrows(IllegalArgumentException::class.java) {
            OnlinePageIdentity.create(chapter, null, 6, null)
        }
    }

    private fun chapter() =
        OnlineChapterIdentity(
            content = OnlineContentIdentity("io.example.source", "comic-1"),
            chapterId = "chapter-1",
        )
}
