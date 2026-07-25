package com.exio.inkleaf.data

import org.junit.Assert.assertEquals
import org.junit.Test

class AlbumProgressTest {
    @Test
    fun `reorder keeps progress on the same page identity`() {
        val result =
            AlbumProgress.remap(
                oldPageIds = listOf("a", "b", "c"),
                newPageIds = listOf("c", "a", "b"),
                lastReadPageId = "b",
                lastReadPageIndex = 1,
            )

        assertEquals(2, result.pageIndex)
        assertEquals("b", result.pageId)
    }

    @Test
    fun `deleting current page prefers the following surviving page`() {
        val result =
            AlbumProgress.remap(
                oldPageIds = listOf("a", "b", "c", "d"),
                newPageIds = listOf("d", "a", "c"),
                lastReadPageId = "b",
                lastReadPageIndex = 1,
            )

        assertEquals(2, result.pageIndex)
        assertEquals("c", result.pageId)
    }

    @Test
    fun `empty album clears stable progress identity`() {
        val result =
            AlbumProgress.remap(
                oldPageIds = listOf("a"),
                newPageIds = emptyList(),
                lastReadPageId = "a",
                lastReadPageIndex = 0,
            )

        assertEquals(0, result.pageIndex)
        assertEquals(null, result.pageId)
    }
}
