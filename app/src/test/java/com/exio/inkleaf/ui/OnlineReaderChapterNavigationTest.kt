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

    @Test
    fun `next readable chapter skips unavailable entries`() {
        assertNull(nextReadableOnlineChapter(chapters, currentChapterIndex = 1))
        val fromFirst = nextReadableOnlineChapter(chapters, currentChapterIndex = 0)
        assertSame(chapters[1], fromFirst?.first)
        assertEquals(1, fromFirst?.second)
    }

    @Test
    fun `previous readable chapter returns null at first chapter`() {
        assertNull(previousReadableOnlineChapter(chapters, currentChapterIndex = 0))
    }

    @Test
    fun `previous readable chapter returns the immediately preceding available chapter`() {
        val fromSecond = previousReadableOnlineChapter(chapters, currentChapterIndex = 1)
        assertSame(chapters[0], fromSecond?.first)
        assertEquals(0, fromSecond?.second)
    }

    @Test
    fun `previous readable chapter skips unavailable entries`() {
        // 紧邻上一项不可用时，继续向前扫描首个可读章节
        val withGap =
            listOf(
                ChapterSummary(chapterId = "chapter-1", title = "第一章"),
                ChapterSummary(chapterId = "chapter-2", title = "第二章", available = false),
                ChapterSummary(chapterId = "chapter-3", title = "第三章"),
            )
        val prev = previousReadableOnlineChapter(withGap, currentChapterIndex = 2)
        assertSame(withGap[0], prev?.first)
        assertEquals(0, prev?.second)
    }

    @Test
    fun `previous readable chapter handles out-of-range index`() {
        assertNull(previousReadableOnlineChapter(chapters, currentChapterIndex = 99))
        assertNull(previousReadableOnlineChapter(emptyList(), currentChapterIndex = 0))
    }
}
