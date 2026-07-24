package com.exio.inkleaf.ui

import com.exio.inkleaf.data.ChapterProgress
import com.exio.inkleaf.data.ComicVolume
import kotlinx.coroutines.runBlocking
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

    @Test
    fun `loader snapshots source chapters including missing entries`() = runBlocking {
        val items = loadReaderChapterItems(
            FakeChapterVolume(
                titles = listOf("", "第二章", "损坏章节", "第四章"),
                pageCounts = listOf(0, 12, 0, 5),
                startPages = listOf(0, 0, 12, 12),
                readable = listOf(false, true, false, true),
            ),
        )

        assertEquals(listOf(0, 1, 2, 3), items.map { it.index })
        assertEquals("第 1 章", items[0].title)
        assertEquals("第二章", items[1].title)
        assertEquals(listOf(0, 12, 0, 5), items.map { it.pageCount })
        assertEquals(listOf(0, 0, 12, 12), items.map { it.startPage })
        assertEquals(listOf(false, true, false, true), items.map { it.isReadable })
    }

    private class FakeChapterVolume(
        private val titles: List<String>,
        private val pageCounts: List<Int>,
        private val startPages: List<Int>,
        private val readable: List<Boolean>,
    ) : ComicVolume {
        override val totalPageCount: Int = pageCounts.sumOf { it.coerceAtLeast(0) }
        override val sourceRevision: String = "fake"
        override val chapterCount: Int = titles.size

        override fun chapterTitle(chapterIndex: Int): String = titles[chapterIndex]

        override fun chapterStartPage(chapterIndex: Int): Int = startPages[chapterIndex]

        override fun chapterPageCount(chapterIndex: Int): Int = pageCounts[chapterIndex]

        override fun isChapterReadable(chapterIndex: Int): Boolean = readable[chapterIndex]

        override fun globalToChapterPage(globalPage: Int): ChapterProgress =
            ChapterProgress(0, globalPage)

        override fun chapterPageToGlobal(chapterIndex: Int, pageIndex: Int): Int =
            startPages[chapterIndex] + pageIndex

        override suspend fun loadPageBytes(globalPage: Int): ByteArray = ByteArray(0)

        override suspend fun loadThumbnailPageBytes(globalPage: Int): ByteArray = ByteArray(0)

        override fun close() = Unit
    }
}
