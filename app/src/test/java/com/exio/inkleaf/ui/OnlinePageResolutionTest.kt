package com.exio.inkleaf.ui

import com.exio.inkleaf.plugin.PageDescriptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OnlinePageResolutionTest {
    @Test
    fun `source page id wins across revision and index changes`() {
        val resolved =
            resolveOnlinePageReference(
                pageId = "page-b",
                fallbackPageIndex = 0,
                fallbackChapterRevision = "revision-1",
                currentChapterRevision = "revision-2",
                pages = pages("page-a", "page-b"),
            )

        assertEquals(1, resolved)
    }

    @Test
    fun `matching revision accepts bounded fallback after page id disappears`() {
        val resolved =
            resolveOnlinePageReference(
                pageId = "missing",
                fallbackPageIndex = 1,
                fallbackChapterRevision = "revision-2",
                currentChapterRevision = "revision-2",
                pages = pages("page-a", "page-b"),
            )

        assertEquals(1, resolved)
    }

    @Test
    fun `fallback rejects changed revision and invalid index`() {
        val pages = pages("page-a", "page-b")

        assertNull(
            resolveOnlinePageReference(
                pageId = "missing",
                fallbackPageIndex = 1,
                fallbackChapterRevision = "revision-1",
                currentChapterRevision = "revision-2",
                pages = pages,
            )
        )
        assertNull(
            resolveOnlinePageReference(
                pageId = null,
                fallbackPageIndex = pages.size,
                fallbackChapterRevision = "revision-2",
                currentChapterRevision = "revision-2",
                pages = pages,
            )
        )
    }

    @Test
    fun `missing revisions preserve legacy index fallback`() {
        val resolved =
            resolveOnlinePageReference(
                pageId = null,
                fallbackPageIndex = 1,
                fallbackChapterRevision = null,
                currentChapterRevision = null,
                pages = pages("page-a", "page-b"),
            )

        assertEquals(1, resolved)
    }

    @Test
    fun `lost source page id without fallback stays unresolved`() {
        assertNull(
            resolveOnlinePageReference(
                pageId = "missing",
                fallbackPageIndex = null,
                fallbackChapterRevision = null,
                currentChapterRevision = "revision-2",
                pages = pages("page-a", "page-b"),
            )
        )
    }

    private fun pages(vararg pageIds: String?): List<PageDescriptor> =
        pageIds.mapIndexed { index, pageId ->
            PageDescriptor(
                pageId = pageId,
                index = index,
                url = "https://example.com/$index",
            )
        }
}
