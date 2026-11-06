package com.exio.inkleaf.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReaderBookmarkPresentationTest {
    @Test
    fun `bookmarks sort by chapter page and insertion time`() {
        val items =
            listOf(
                item(key = "late", chapterOrder = 0, pageIndex = 2, addedAtMs = 10),
                item(key = "early", chapterOrder = 0, pageIndex = 2, addedAtMs = 5),
                item(key = "first", chapterOrder = 0, pageIndex = 1, addedAtMs = 99),
                item(key = "next", chapterOrder = 1, pageIndex = 0, addedAtMs = 1),
            )

        assertEquals(
            listOf("first", "early", "late", "next"),
            sortedReaderBookmarks(items).map { it.key },
        )
    }

    @Test
    fun `unavailable chapters sort last even with a small order`() {
        val items =
            listOf(
                item(
                    key = "missing",
                    chapterOrder = 0,
                    pageIndex = 0,
                    addedAtMs = 1,
                    available = false,
                ),
                item(key = "known", chapterOrder = 3, pageIndex = 0, addedAtMs = 2),
            )

        assertEquals(
            listOf("known", "missing"),
            sortedReaderBookmarks(items).map { it.key },
        )
    }

    @Test
    fun `unknown page numbers follow known pages within a chapter`() {
        val items =
            listOf(
                item(key = "unknown", chapterOrder = 0, pageIndex = null, addedAtMs = 1),
                item(key = "known", chapterOrder = 0, pageIndex = 4, addedAtMs = 2),
            )

        assertEquals(
            listOf("known", "unknown"),
            sortedReaderBookmarks(items).map { it.key },
        )
    }

    @Test
    fun `page label prefers global and chapter page together`() {
        assertEquals(
            "全书第 21 页 · 章节第 11 页",
            readerBookmarkPageLabel(
                item(key = "local", chapterOrder = 1, pageIndex = 10, globalPage = 20)
            ),
        )
    }

    @Test
    fun `page label shows only the chapter page when global page is unknown`() {
        assertEquals(
            "章节第 11 页",
            readerBookmarkPageLabel(item(key = "online", chapterOrder = 1, pageIndex = 10)),
        )
    }

    @Test
    fun `page label shows only the global page when chapter page is unknown`() {
        assertEquals(
            "全书第 21 页",
            readerBookmarkPageLabel(item(key = "local", chapterOrder = 1, globalPage = 20)),
        )
    }

    @Test
    fun `page label is omitted when no page is trustworthy`() {
        assertNull(readerBookmarkPageLabel(item(key = "online", chapterOrder = 0)))
    }

    private fun item(
        key: String,
        chapterOrder: Int,
        pageIndex: Int? = null,
        globalPage: Int? = null,
        addedAtMs: Long = 0L,
        available: Boolean = true,
    ): ReaderBookmarkItem =
        ReaderBookmarkItem(
            key = key,
            target = ReaderBookmarkTarget.Local(globalPage ?: 0),
            chapterOrder = chapterOrder,
            pageIndex = pageIndex,
            globalPage = globalPage,
            chapterTitle = "章节",
            stale = false,
            available = available,
            addedAtMs = addedAtMs,
            thumbnailPage = globalPage,
        )
}
