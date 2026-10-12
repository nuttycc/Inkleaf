package com.exio.inkleaf.ui

import com.exio.inkleaf.data.ChapterMetadata
import com.exio.inkleaf.data.ChapterProgress
import com.exio.inkleaf.data.ComicVolume
import com.exio.inkleaf.data.PageRenderRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PageIdentityTest {
    private val volume = FakeVolume(sourceRevision = "rev-a", pageIdentities = listOf("p0", "p1"))

    @Test
    fun `same inputs reuse state and producer keys`() {
        val before = keys()
        val after = keys()

        assertEquals(before.stateReset, after.stateReset)
        assertEquals(before.producerRestart, after.producerRestart)
    }

    @Test
    fun `PDF render request changes restart without resetting displayed state`() {
        val before = keys(pageRenderRequest = renderRequest(1080, 1920))
        val after = keys(pageRenderRequest = renderRequest(3240, 5760))

        assertEquals(before.stateReset, after.stateReset)
        assertNotEquals(before.producerRestart, after.producerRestart)
    }

    @Test
    fun `page identity changes use a new state holder`() {
        val before = keys()

        assertNotEquals(before.stateReset, keys(page = 8).stateReset)
        assertNotEquals(before.stateReset, keys(volumeToken = Any()).stateReset)
        assertNotEquals(before.stateReset, keys(cacheKeyPrefix = "local:rev-b").stateReset)
    }

    /** The bug this module exists for: a stale shelf id never rotated when content changed. */
    @Test
    fun `namespace rotates when the volume content changes`() {
        val before = PageIdentity.stateKey(volume, page = 0)
        val repacked = FakeVolume(sourceRevision = "rev-b", pageIdentities = listOf("p0", "p1"))

        assertEquals("local:rev-a", before.namespace)
        assertEquals("local:rev-b", PageIdentity.stateKey(repacked, page = 0).namespace)
        assertNotEquals(before, PageIdentity.stateKey(repacked, page = 0))
    }

    @Test
    fun `pages without their own identity fall back to their position`() {
        val opaque = FakeVolume(sourceRevision = "rev-a", pageIdentities = listOf(null, null))

        assertEquals("index:0", PageIdentity.pageIdentity(opaque, page = 0))
        assertEquals("index:1", PageIdentity.stateKey(opaque, page = 1).pageIdentity)
    }

    @Test
    fun `a volume's own page identity wins over the positional fallback`() {
        assertEquals("p1", PageIdentity.pageIdentity(volume, page = 1))
    }

    /** An online page is keyed by its chapter revision rather than by a volume token. */
    @Test
    fun `online chapter keys carry the chapter revision`() {
        val key = PageIdentity.stateKey(chapterId = "ch-1", chapterRevision = "rev-9", pageIdentity = "p3")

        assertEquals("ch-1\u0000rev-9", key.namespace)
        assertEquals("p3", key.pageIdentity)
        assertEquals(key, ReaderChapterPageKey("ch-1", "rev-9", "p3").toReaderPageStateKey())
    }

    @Test
    fun `keys differ across namespaces for the same page identity`() {
        val local = PageIdentity.stateKey(volume, page = 0)
        val online = PageIdentity.stateKey(chapterId = "ch-1", chapterRevision = "rev-9", pageIdentity = "p0")

        assertNotEquals(local, online)
    }

    private fun keys(
        volumeToken: Any = volume,
        page: Int = 7,
        cacheKeyPrefix: String = "local:rev-a",
        pageRenderRequest: PageRenderRequest? = null,
    ) =
        readerPageContentKeys(
            volumeToken = volumeToken,
            page = page,
            cacheKeyPrefix = cacheKeyPrefix,
            pageRenderRequest = pageRenderRequest,
        )

    private fun renderRequest(width: Int, height: Int) =
        PageRenderRequest(
            maxWidthPx = width,
            maxHeightPx = height,
            maxPixels = 8_000_000,
            maxDimensionPx = 4096,
        )

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

        override fun globalToChapterPage(globalPage: Int): ChapterProgress = ChapterProgress(0, globalPage)

        override fun chapterPageToGlobal(chapterIndex: Int, pageIndex: Int): Int = pageIndex

        override fun pageIdentity(globalPage: Int): String? = pageIdentities.getOrNull(globalPage)

        override suspend fun loadPageBytes(globalPage: Int): ByteArray = ByteArray(0)

        override suspend fun loadThumbnailPageBytes(globalPage: Int): ByteArray = ByteArray(0)

        override fun close() = Unit
    }
}
