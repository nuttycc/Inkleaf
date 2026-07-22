package com.exio.inkleaf.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ReaderPageCacheKeyTest {
    @Test
    fun `reusing an index for a different album page changes the cache key`() {
        val oldKey = ReaderPageCacheKey.forPage("comic-42", page = 1, pageIdentity = "b")
        val newKey = ReaderPageCacheKey.forPage("comic-42", page = 1, pageIdentity = "c")

        assertNotEquals(oldKey, newKey)
    }

    @Test
    fun `same album page keeps its cache key when its index changes`() {
        val oldKey = ReaderPageCacheKey.forPage("comic-42", page = 1, pageIdentity = "c")
        val newKey = ReaderPageCacheKey.forPage("comic-42", page = 2, pageIdentity = "c")

        assertEquals(oldKey, newKey)
    }

    @Test
    fun `album thumbnail filename follows page identity instead of index`() {
        val oldFile = ReaderPageCacheKey.thumbnailFileName(page = 1, pageIdentity = "c")
        val newFile = ReaderPageCacheKey.thumbnailFileName(page = 2, pageIdentity = "c")

        assertEquals(oldFile, newFile)
        assertNotEquals(oldFile, ReaderPageCacheKey.thumbnailFileName(page = 1, pageIdentity = "b"))
    }

    @Test
    fun `source revision changes the page cache key`() {
        val oldKey = ReaderPageCacheKey.forPage(
            "comic-42",
            page = 1,
            pageIdentity = "c",
            sourceRevision = "old-source",
        )
        val newKey = ReaderPageCacheKey.forPage(
            "comic-42",
            page = 1,
            pageIdentity = "c",
            sourceRevision = "new-source",
        )

        assertNotEquals(oldKey, newKey)
    }

    @Test
    fun `source revision token is stable for the same metadata`() {
        assertEquals(
            ReaderPageCacheKey.sourceRevision(listOf("zip", "100", "200")),
            ReaderPageCacheKey.sourceRevision(listOf("zip", "100", "200")),
        )
    }
}
