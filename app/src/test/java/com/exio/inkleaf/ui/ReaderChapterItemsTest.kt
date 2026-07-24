package com.exio.inkleaf.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderChapterItemsTest {
    @Test
    fun `chapter menu is only shown for multi chapter volumes`() {
        assertFalse(shouldShowChapterMenu(0))
        assertFalse(shouldShowChapterMenu(1))
        assertTrue(shouldShowChapterMenu(2))
    }

    @Test
    fun `chapter items preserve source order and expose navigation state`() {
        val items = buildReaderChapterItems(
            chapterCount = 3,
            titleOf = { index -> if (index == 0) "" else "Chapter ${index + 1}" },
            pageCountOf = { index -> listOf(12, 0, -1)[index] },
            startPageOf = { index -> listOf(0, 12, 12)[index] },
            readableOf = { _, _ -> true },
        )

        assertEquals(listOf(0, 1, 2), items.map { it.index })
        assertEquals("第 1 章", items[0].title)
        assertEquals("Chapter 2", items[1].title)
        assertTrue(items[0].isReadable)
        assertFalse(items[1].isReadable)
        assertFalse(items[2].isReadable)
        assertEquals(0, items[2].pageCount)
        assertEquals(12, items[2].startPage)
    }
}
