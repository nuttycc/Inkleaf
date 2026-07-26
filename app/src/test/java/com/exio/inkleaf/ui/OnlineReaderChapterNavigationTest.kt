package com.exio.inkleaf.ui

import com.exio.inkleaf.plugin.ChapterSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineReaderChapterNavigationTest {
    private val chapters =
        listOf(
            ChapterSummary(chapterId = "chapter-1", title = ""),
            ChapterSummary(chapterId = "chapter-2", title = "第二章"),
            ChapterSummary(chapterId = "chapter-3", title = "第三章", available = false),
        )

    @Test
    fun `online chapters keep unknown page counts readable`() {
        val items = buildOnlineReaderChapterItems(chapters)

        assertEquals(listOf(0, 1, 2), items.map { it.index })
        assertEquals("第 1 章", items[0].title)
        assertTrue(items[0].isReadable)
        assertNull(items[0].pageCount)
        assertFalse(items[2].isReadable)
    }

    @Test
    fun `selection ignores current unavailable and invalid chapters`() {
        assertNull(
            selectableOnlineChapter(chapters, currentChapterIndex = 1, targetChapterIndex = 1)
        )
        assertNull(
            selectableOnlineChapter(chapters, currentChapterIndex = 1, targetChapterIndex = 2)
        )
        assertNull(
            selectableOnlineChapter(chapters, currentChapterIndex = 1, targetChapterIndex = 99)
        )
        assertSame(
            chapters[0],
            selectableOnlineChapter(chapters, currentChapterIndex = 1, targetChapterIndex = 0),
        )
    }
}
