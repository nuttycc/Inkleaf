package com.exio.inkleaf.ui

import com.exio.inkleaf.data.OnlineChapterIdentity
import com.exio.inkleaf.data.OnlineContentIdentity
import com.exio.inkleaf.data.OnlinePageLocation
import com.exio.inkleaf.plugin.ChapterSummary
import com.exio.inkleaf.plugin.OnlinePageBookmark
import com.exio.inkleaf.plugin.PageDescriptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineBookmarkAggregationTest {
    private val content = OnlineContentIdentity("plugin", "book")
    private val chapters =
        listOf(
            ChapterSummary(chapterId = "c1", title = "第一话", revision = "r1"),
            ChapterSummary(chapterId = "c2", title = "第二话", revision = "r2"),
        )
    private val pages =
        (0 until 3).map { index ->
            PageDescriptor(pageId = "p$index", index = index, url = "https://example/$index")
        }

    @Test
    fun `fallback bookmark in another chapter keeps its recorded page`() {
        val bookmark =
            fallbackBookmark(
                chapterId = "c2",
                pageIndex = 4,
                chapterRevision = "r2",
                addedAtMs = 10L,
            )

        val item = singleItem(bookmark, currentChapterId = "c1", currentRevision = "r1")

        assertEquals(1, item.chapterOrder)
        assertEquals(4, item.pageIndex)
        assertEquals("第二话", item.chapterTitle)
        assertFalse(item.stale)
        assertTrue(item.available)
        assertNull(item.globalPage)
        assertNull(item.thumbnailPage)
    }

    @Test
    fun `page id bookmark in another chapter does not invent a page number`() {
        val bookmark =
            pageIdBookmark(
                chapterId = "c2",
                pageId = "p1",
                pageIndex = 1,
                addedAtMs = 20L,
                chapterTitleSnapshot = "第二话快照",
            )

        val item = singleItem(bookmark, currentChapterId = "c1", currentRevision = "r1")

        assertEquals(1, item.chapterOrder)
        assertNull(item.pageIndex)
        assertNull(item.thumbnailPage)
        assertEquals("第二话快照", item.chapterTitle)
        assertEquals(
            ReaderBookmarkTarget.Online("c2", chapterPageIndex = null, pageId = "p1"),
            item.target,
        )
    }

    @Test
    fun `missing chapter sorts last and is unavailable`() {
        val bookmark = pageIdBookmark("gone", "p1", 1, 30L, chapterTitleSnapshot = "已下架")

        val item = singleItem(bookmark, currentChapterId = "c1", currentRevision = "r1")

        assertEquals(Int.MAX_VALUE, item.chapterOrder)
        assertEquals("已下架", item.chapterTitle)
        assertFalse(item.available)
        assertNull(item.pageIndex)
        assertNull(item.thumbnailPage)
    }

    @Test
    fun `unavailable chapter is marked unavailable`() {
        val unavailableChapters =
            listOf(
                ChapterSummary(chapterId = "c1", title = "第一话", revision = "r1"),
                ChapterSummary(
                    chapterId = "c2",
                    title = "第二话",
                    revision = "r2",
                    available = false,
                ),
            )
        val bookmark = fallbackBookmark("c2", 4, "r2", 40L)

        val item =
            onlineBookmarkItems(
                    bookmarks = listOf(bookmark),
                    chapters = unavailableChapters,
                    currentChapterId = "c1",
                    currentChapterRevision = "r1",
                    currentChapterPageCount = pages.size,
                    currentPageDescriptors = pages,
                    keyOf = { it.chapter.chapterId },
                )
                .single()

        assertFalse(item.available)
        assertEquals(Int.MAX_VALUE, item.chapterOrder)
        assertEquals("第二话", item.chapterTitle)
    }

    @Test
    fun `current chapter page id resolves exactly and stays thumbnailable`() {
        val bookmark = pageIdBookmark("c1", "p2", 2, 50L)

        val item = singleItem(bookmark, currentChapterId = "c1", currentRevision = "r1")

        assertEquals(0, item.chapterOrder)
        assertEquals(2, item.pageIndex)
        assertEquals(2, item.thumbnailPage)
        assertFalse(item.stale)
    }

    @Test
    fun `current chapter unresolved fallback clamps the page and marks it stale`() {
        val bookmark = fallbackBookmark("c1", 7, "r0", 60L)

        val item = singleItem(bookmark, currentChapterId = "c1", currentRevision = "r1")

        assertEquals(pages.lastIndex, item.pageIndex)
        assertTrue(item.stale)
    }

    @Test
    fun `changed chapter revision requires confirmation`() {
        val bookmark = pageIdBookmark("c2", "p1", 1, 70L, chapterRevision = "r1")

        val item = singleItem(bookmark, currentChapterId = "c1", currentRevision = "r1")

        assertTrue(item.stale)
        assertNull(item.pageIndex)
    }

    @Test
    fun `old json without title snapshot falls back to chapter title`() {
        val bookmark = pageIdBookmark("c2", "p1", 1, 80L, chapterTitleSnapshot = null)

        val item = singleItem(bookmark, currentChapterId = "c1", currentRevision = "r1")

        assertEquals("第二话", item.chapterTitle)
        assertFalse(item.stale)
    }

    @Test
    fun `bookmarks are ordered by chapter then page then insertion time`() {
        val items =
            onlineBookmarkItems(
                bookmarks =
                    listOf(
                        fallbackBookmark("c2", 1, "r2", 10L),
                        fallbackBookmark("c1", 2, "r1", 20L),
                        fallbackBookmark("c1", 1, "r1", 30L),
                        pageIdBookmark("gone", "p1", 1, 5L),
                    ),
                chapters = chapters,
                currentChapterId = "c1",
                currentChapterRevision = "r1",
                currentChapterPageCount = pages.size,
                currentPageDescriptors = pages,
                keyOf = { it.chapter.chapterId + ":" + it.pageId + ":" + it.fallback?.pageIndex },
            )

        val ordered = sortedReaderBookmarks(items)

        assertEquals(
            listOf("c1:null:1", "c1:null:2", "c2:null:1", "gone:p1:null"),
            ordered.map { it.key },
        )
    }

    @Test
    fun `jump resolves a stable page id exactly`() {
        val jump =
            resolveOnlineBookmarkJump(
                target = ReaderBookmarkTarget.Online("c2", chapterPageIndex = 1, pageId = "p1"),
                currentRevision = "r2",
                pages = pages,
            )

        assertEquals(1, jump.page)
        assertFalse(jump.approximate)
    }

    @Test
    fun `jump falls back to a clamped recorded page`() {
        val inRange =
            resolveOnlineBookmarkJump(
                target = ReaderBookmarkTarget.Online("c2", chapterPageIndex = 1, pageId = null),
                currentRevision = "r2",
                pages = pages,
            )
        val outOfRange =
            resolveOnlineBookmarkJump(
                target = ReaderBookmarkTarget.Online("c2", chapterPageIndex = 9, pageId = null),
                currentRevision = "r2",
                pages = pages,
            )

        assertEquals(1, inRange.page)
        assertFalse(inRange.approximate)
        assertEquals(0, outOfRange.page)
        assertTrue(outOfRange.approximate)
    }

    @Test
    fun `jump marks a missing page id as approximate`() {
        val jump =
            resolveOnlineBookmarkJump(
                target =
                    ReaderBookmarkTarget.Online("c2", chapterPageIndex = 1, pageId = "missing"),
                currentRevision = "r2",
                pages = pages,
            )

        assertEquals(1, jump.page)
        assertTrue(jump.approximate)
    }

    private fun singleItem(
        bookmark: OnlinePageBookmark,
        currentChapterId: String,
        currentRevision: String,
    ): ReaderBookmarkItem =
        onlineBookmarkItems(
                bookmarks = listOf(bookmark),
                chapters = chapters,
                currentChapterId = currentChapterId,
                currentChapterRevision = currentRevision,
                currentChapterPageCount = pages.size,
                currentPageDescriptors = pages,
                keyOf = { it.chapter.chapterId },
            )
            .single()

    private fun pageIdBookmark(
        chapterId: String,
        pageId: String,
        pageIndex: Int,
        addedAtMs: Long,
        chapterTitleSnapshot: String? = "第二话",
        chapterRevision: String? = null,
    ): OnlinePageBookmark =
        OnlinePageBookmark(
            location =
                OnlinePageLocation.create(
                    chapter = OnlineChapterIdentity(content, chapterId),
                    pageId = pageId,
                    pageIndex = pageIndex,
                    chapterRevision = chapterRevision,
                ),
            chapterTitleSnapshot = chapterTitleSnapshot,
            addedAtMs = addedAtMs,
        )

    private fun fallbackBookmark(
        chapterId: String,
        pageIndex: Int,
        chapterRevision: String,
        addedAtMs: Long,
    ): OnlinePageBookmark =
        OnlinePageBookmark(
            location =
                OnlinePageLocation.create(
                    chapter = OnlineChapterIdentity(content, chapterId),
                    pageId = null,
                    pageIndex = pageIndex,
                    chapterRevision = chapterRevision,
                ),
            chapterTitleSnapshot = null,
            addedAtMs = addedAtMs,
        )
}
