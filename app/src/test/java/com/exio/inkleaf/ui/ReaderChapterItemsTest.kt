package com.exio.inkleaf.ui

import com.exio.inkleaf.data.ChapterMetadata
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
    fun `chapter list starts one row before the current chapter`() {
        assertEquals(0, readerChapterInitialListIndex(chapterCount = 0, currentChapterIndex = 8))
        assertEquals(0, readerChapterInitialListIndex(chapterCount = 12, currentChapterIndex = 0))
        assertEquals(4, readerChapterInitialListIndex(chapterCount = 12, currentChapterIndex = 5))
        assertEquals(11, readerChapterInitialListIndex(chapterCount = 12, currentChapterIndex = 99))
    }

    @Test
    fun `chapter items preserve source order and expose navigation state`() {
        val items =
            buildReaderChapterItems(
                chapterCount = 3,
                titleOf = { index -> if (index == 0) "" else "Chapter ${index + 1}" },
                pageCountOf = { index -> listOf(12, 0, -1)[index] },
                readableOf = { _, _ -> true },
            )

        assertEquals(listOf(0, 1, 2), items.map { it.index })
        assertEquals("第 1 章", items[0].title)
        assertEquals("Chapter 2", items[1].title)
        assertTrue(items[0].isReadable)
        assertFalse(items[1].isReadable)
        assertFalse(items[2].isReadable)
        assertEquals(0, items[2].pageCount)
    }

    @Test
    fun `loader snapshots source chapters including missing entries`() = runBlocking {
        val volume =
            FakeChapterVolume(
                titles = listOf("", "第二章", "损坏章节", "第四章"),
                pageCounts = listOf(0, 12, 0, 5),
                startPages = listOf(0, 0, 12, 12),
                readable = listOf(false, true, false, true),
            )
        val items = loadReaderChapterItems(volume)

        assertEquals(listOf(0, 1, 2, 3), items.map { it.index })
        assertEquals("第 1 章", items[0].title)
        assertEquals("第二章", items[1].title)
        assertEquals(listOf(0, 12, 0, 5), items.map { it.pageCount })
        assertEquals(listOf(false, true, false, true), items.map { it.isReadable })
        assertEquals(1, volume.metadataProbeCount)
        assertEquals(0, volume.individualReadabilityProbeCount)
    }

    @Test
    fun `first readable chapter probe stops in source order`() {
        val volume =
            FakeChapterVolume(
                titles = listOf("损坏章节", "第二章", "第三章"),
                pageCounts = listOf(0, 4, 8),
                startPages = listOf(0, 0, 4),
                readable = listOf(false, true, true),
            )

        assertEquals(1, volume.firstReadableChapterIndex())
        assertEquals(2, volume.individualReadabilityProbeCount)
    }

    private class FakeChapterVolume(
        private val titles: List<String>,
        private val pageCounts: List<Int>,
        private val startPages: List<Int>,
        private val readable: List<Boolean>,
    ) : ComicVolume {
        var metadataProbeCount = 0
        var individualReadabilityProbeCount = 0

        override val totalPageCount: Int = pageCounts.sumOf { it.coerceAtLeast(0) }
        override val sourceRevision: String = "fake"
        override val chapterCount: Int = titles.size

        override fun chapterTitle(chapterIndex: Int): String = titles[chapterIndex]

        override fun chapterStartPage(chapterIndex: Int): Int = startPages[chapterIndex]

        override fun chapterPageCount(chapterIndex: Int): Int = pageCounts[chapterIndex]

        override fun isChapterReadable(chapterIndex: Int): Boolean {
            individualReadabilityProbeCount++
            return readable[chapterIndex]
        }

        override fun probeChapterMetadata(): List<ChapterMetadata> {
            metadataProbeCount++
            return pageCounts.indices.map { index ->
                ChapterMetadata(pageCounts[index], readable[index])
            }
        }

        override fun globalToChapterPage(globalPage: Int): ChapterProgress =
            ChapterProgress(0, globalPage)

        override fun chapterPageToGlobal(chapterIndex: Int, pageIndex: Int): Int =
            startPages[chapterIndex] + pageIndex

        override suspend fun loadPageBytes(globalPage: Int): ByteArray = ByteArray(0)

        override suspend fun loadThumbnailPageBytes(globalPage: Int): ByteArray = ByteArray(0)

        override fun close() = Unit
    }
}
