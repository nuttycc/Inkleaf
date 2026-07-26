package com.exio.inkleaf.plugin

import com.exio.inkleaf.data.ComicOpenException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineChapterVolumeTest {
    @Test
    fun `ordered page ids provide a URL independent revision fallback`() {
        val first = response(pageIds = listOf("page-1", "page-2"))
        val sameIdsDifferentUrls =
            response(pageIds = listOf("page-1", "page-2"), urlSuffix = "-new")
        val reordered = response(pageIds = listOf("page-2", "page-1"))

        val revision = resolveOnlineChapterRevision(CHAPTER_ID, null, first)

        assertTrue(revision.startsWith("page-ids:"))
        assertEquals(revision, resolveOnlineChapterRevision(CHAPTER_ID, null, sameIdsDifferentUrls))
        assertNotEquals(revision, resolveOnlineChapterRevision(CHAPTER_ID, null, reordered))
    }

    @Test
    fun `missing page id requires an explicit revision`() {
        val response = response(pageIds = listOf(null))

        assertThrows(ComicOpenException::class.java) {
            resolveOnlineChapterRevision(CHAPTER_ID, null, response)
        }
        assertEquals(
            "chapter-r1",
            resolveOnlineChapterRevision(CHAPTER_ID, "chapter-r1", response),
        )
    }

    private fun response(
        pageIds: List<String?>,
        urlSuffix: String = "",
    ): PluginPagesResponse =
        PluginPagesResponse(
            sourceId = "comic-1",
            chapterId = CHAPTER_ID,
            pages =
                pageIds.mapIndexed { index, pageId ->
                    PageDescriptor(
                        pageId = pageId,
                        index = index,
                        url = "https://example.com/page-$index$urlSuffix.jpg",
                    )
                },
        )

    private companion object {
        const val CHAPTER_ID = "chapter-1"
    }
}
