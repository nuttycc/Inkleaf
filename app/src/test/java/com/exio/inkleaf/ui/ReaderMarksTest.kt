package com.exio.inkleaf.ui

import com.exio.inkleaf.data.ChapterMetadata
import com.exio.inkleaf.data.ChapterProgress
import com.exio.inkleaf.data.ComicVolume
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderMarksTest {
    private val volume = FakeVolume(sourceRevision = "rev-a", pageIdentities = listOf("p0", null))

    @Test
    fun `a re-resolved page is the same page`() {
        val before = ReaderPageRef(namespace = "local:rev-a", pageIdentity = "p0", anchorPage = 0)
        val after = ReaderPageRef(namespace = "local:rev-a", pageIdentity = "p0", anchorPage = 9)

        assertEquals(before, after)
        assertEquals(before.hashCode(), after.hashCode())
    }

    @Test
    fun `different namespaces are different pages`() {
        val local = ReaderPageRef(namespace = "local:rev-a", pageIdentity = "p0", anchorPage = 0)
        val other = ReaderPageRef(namespace = "local:rev-b", pageIdentity = "p0", anchorPage = 0)

        assertNotEquals(local, other)
    }

    @Test
    fun `a volume page reference carries its namespace and positional fallback`() {
        val first = PageIdentity.refOf(volume, page = 0)
        val second = PageIdentity.refOf(volume, page = 1)

        assertEquals("local:rev-a", first.namespace)
        assertEquals("p0", first.pageIdentity)
        assertEquals("index:1", second.pageIdentity)
        assertEquals(second.anchorPage, 1)
    }

    @Test
    fun `a volume page reference rotates when the content changes`() {
        val before = PageIdentity.refOf(volume, page = 0)
        val repacked = FakeVolume(sourceRevision = "rev-b", pageIdentities = listOf("p0", null))

        assertNotEquals(before, PageIdentity.refOf(repacked, page = 0))
    }

    @Test
    fun `the state key derives from the page reference`() {
        val ref = PageIdentity.refOf(volume, page = 0)

        assertEquals(PageIdentity.stateKey(volume, page = 0), ref.stateKey())
        assertEquals(
            ReaderPageStateKey(namespace = "local:rev-a", pageIdentity = "p0"),
            ref.stateKey(),
        )
    }

    @Test
    fun `toggle outcomes read as one message each`() {
        assertEquals("已添加书签", ReaderMarkOutcome.Added(ReaderMarkKind.BOOKMARK).message())
        assertEquals("已收藏", ReaderMarkOutcome.Added(ReaderMarkKind.FAVORITE).message())
        assertEquals("已移除书签", ReaderMarkOutcome.Removed(ReaderMarkKind.BOOKMARK).message())
        assertEquals("已取消收藏", ReaderMarkOutcome.Removed(ReaderMarkKind.FAVORITE).message())
    }

    @Test
    fun `failures keep their cause and an ignored tap says nothing`() {
        val bookmarkFailure = ReaderMarkOutcome.Failed(ReaderMarkKind.BOOKMARK, IOException("磁盘已满"))
        val favoriteFailure = ReaderMarkOutcome.Failed(ReaderMarkKind.FAVORITE, IOException())

        assertEquals("书签操作失败：磁盘已满", bookmarkFailure.message())
        assertEquals("收藏失败", favoriteFailure.message())
        assertNull(ReaderMarkOutcome.Ignored.message())
    }

    @Test
    fun `the gate drops a second tap on the same page only`() {
        val gate = ReaderMarkGate()
        val ref = ReaderPageRef(namespace = "local:rev-a", pageIdentity = "p0", anchorPage = 0)

        assertTrue(gate.enter(ref))
        assertFalse(gate.enter(ReaderPageRef("local:rev-a", "p0", anchorPage = 4)))
        assertTrue(gate.enter(ReaderPageRef("local:rev-a", "index:4", anchorPage = 4)))

        gate.leave(ref)
        assertTrue(gate.enter(ref))
    }

    private class FakeVolume(
        override val sourceRevision: String,
        private val pageIdentities: List<String?>,
    ) : ComicVolume {
        override val totalPageCount: Int = pageIdentities.size
        override val chapterCount: Int = 1

        override fun chapterTitle(chapterIndex: Int): String = "Ch 1"

        override fun chapterStartPage(chapterIndex: Int): Int = 0

        override fun chapterPageCount(chapterIndex: Int): Int = totalPageCount

        override fun probeChapterMetadata(): List<ChapterMetadata> =
            listOf(ChapterMetadata(totalPageCount, true))

        override fun globalToChapterPage(globalPage: Int): ChapterProgress =
            ChapterProgress(0, globalPage)

        override fun chapterPageToGlobal(chapterIndex: Int, pageIndex: Int): Int = pageIndex

        override fun pageIdentity(globalPage: Int): String? = pageIdentities.getOrNull(globalPage)

        override suspend fun loadPageBytes(globalPage: Int): ByteArray = ByteArray(0)

        override suspend fun loadThumbnailPageBytes(globalPage: Int): ByteArray = ByteArray(0)

        override fun close() = Unit
    }
}
