package com.exio.inkleaf.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderChapterWindowTest {
    @Test
    fun `pager keys are stable strings for every window item type`() {
        val window =
            buildReaderChapterWindow(
                active = chapter("chapter-1", index = 0),
                previous = null,
                next = adjacent(ReaderTransitionDirection.NEXT, chapter("chapter-2", 1)),
            )

        val firstPass = window.items.map(ReaderChapterWindowItem<*>::saveablePagerKey)
        val secondPass = window.items.map(ReaderChapterWindowItem<*>::saveablePagerKey)

        assertEquals(firstPass, secondPass)
        assertEquals(firstPass.size, firstPass.distinct().size)
        assertTrue(firstPass.all { it.isNotBlank() })
    }

    @Test
    fun `pager page keys distinguish chapter revision and page identity`() {
        val base = ReaderChapterWindowItem.Page(chapter("chapter-1", revision = "rev-a"), 0)

        assertNotEquals(
            base.saveablePagerKey(),
            ReaderChapterWindowItem.Page(chapter("chapter-2", revision = "rev-a"), 0)
                .saveablePagerKey(),
        )
        assertNotEquals(
            base.saveablePagerKey(),
            ReaderChapterWindowItem.Page(chapter("chapter-1", revision = "rev-b"), 0)
                .saveablePagerKey(),
        )
        assertNotEquals(
            base.saveablePagerKey(),
            ReaderChapterWindowItem.Page(chapter("chapter-1", revision = "rev-a"), 1)
                .saveablePagerKey(),
        )
    }

    @Test
    fun `pager key encoding cannot collide through separators`() {
        val first =
            ReaderChapterWindowItem.Page(
                ReaderWindowChapter(
                    chapterId = "a:b",
                    chapterIndex = 0,
                    chapterRevision = "c",
                    pageIdentities = listOf("d"),
                    payload = Unit,
                ),
                pageIndex = 0,
            )
        val second =
            ReaderChapterWindowItem.Page(
                ReaderWindowChapter(
                    chapterId = "a",
                    chapterIndex = 0,
                    chapterRevision = "b:c",
                    pageIdentities = listOf("d"),
                    payload = Unit,
                ),
                pageIndex = 0,
            )

        assertNotEquals(first.saveablePagerKey(), second.saveablePagerKey())
    }

    @Test
    fun `next boundary uses the current chapter last page as its context`() {
        val current = chapter("chapter-1", index = 0)
        val window =
            buildReaderChapterWindow(
                active = current,
                previous = null,
                next = adjacent(ReaderTransitionDirection.NEXT, chapter("chapter-2", 1), true),
            )
        val boundaryIndex = window.items.indexOfFirst { it is ReaderChapterWindowItem.Boundary }

        assertEquals(current.pageKey(1), window.contextPageAt(boundaryIndex).pageKey)
    }

    @Test
    fun `previous boundary uses the current chapter first page as its context`() {
        val current = chapter("chapter-2", index = 1)
        val window =
            buildReaderChapterWindow(
                active = current,
                previous = adjacent(ReaderTransitionDirection.PREVIOUS, chapter("chapter-1", 0), true),
                next = null,
            )
        val boundaryIndex = window.items.indexOfFirst { it is ReaderChapterWindowItem.Boundary }

        assertEquals(current.pageKey(0), window.contextPageAt(boundaryIndex).pageKey)
    }

    @Test
    fun `unprepared boundaries keep the source chapter edge page as their context`() {
        val current = chapter("chapter-2", index = 1)
        val previousWindow =
            buildReaderChapterWindow(
                active = current,
                previous = adjacent(ReaderTransitionDirection.PREVIOUS, chapter("chapter-1", 0)),
                next = null,
            )
        val nextWindow =
            buildReaderChapterWindow(
                active = current,
                previous = null,
                next = adjacent(ReaderTransitionDirection.NEXT, chapter("chapter-3", 2)),
            )

        assertTrue(previousWindow.items.first() is ReaderChapterWindowItem.Boundary)
        assertEquals(current.pageKey(0), previousWindow.contextPageAt(0).pageKey)
        assertTrue(nextWindow.items.last() is ReaderChapterWindowItem.Boundary)
        assertEquals(
            current.pageKey(1),
            nextWindow.contextPageAt(nextWindow.items.lastIndex).pageKey,
        )
    }

    @Test
    fun `stale pager index falls back to a valid active chapter page`() {
        val current = chapter("chapter-2", index = 1)
        val window =
            buildReaderChapterWindow(
                active = current,
                previous = null,
                next = adjacent(ReaderTransitionDirection.NEXT, chapter("chapter-3", 2), true),
            )

        assertEquals(current.pageKey(0), window.contextPageAt(Int.MAX_VALUE).pageKey)
    }

    @Test
    fun `page keys distinguish chapters revisions and pages`() {
        val chapter = chapter("chapter-1", revision = "rev-a")

        assertNotEquals(chapter.pageKey(0), chapter("chapter-2").pageKey(0))
        assertNotEquals(chapter.pageKey(0), chapter("chapter-1", revision = "rev-b").pageKey(0))
        assertNotEquals(chapter.pageKey(0), chapter.pageKey(1))
        assertEquals(chapter.pageKey(0), chapter("chapter-1", revision = "rev-a").pageKey(0))
    }

    @Test
    fun `window keeps boundary between current and prepared next chapter`() {
        val current = chapter("chapter-1", index = 0)
        val next = chapter("chapter-2", index = 1)
        val window =
            buildReaderChapterWindow(
                active = current,
                previous = null,
                next = adjacent(ReaderTransitionDirection.NEXT, next, prepared = true),
            )

        assertEquals(
            listOf("chapter-1:0", "chapter-1:1", "boundary", "chapter-2:0", "chapter-2:1"),
            window.items.map(::label),
        )
    }

    @Test
    fun `boundary identity is stable when its active side changes`() {
        val forward = ReaderChapterBoundaryKey("chapter-1", "chapter-2")
        val reverse = ReaderChapterBoundaryKey("chapter-1", "chapter-2")

        assertEquals(forward, reverse)
        assertNotEquals(forward, ReaderChapterBoundaryKey("chapter-2", "chapter-3"))
    }

    @Test
    fun `unprepared next chapter stops at its boundary`() {
        val window =
            buildReaderChapterWindow(
                active = chapter("chapter-1", index = 0),
                previous = null,
                next = adjacent(ReaderTransitionDirection.NEXT, chapter("chapter-2", 1)),
            )

        assertEquals(
            listOf("chapter-1:0", "chapter-1:1", "boundary"),
            window.items.map(::label),
        )
        assertEquals(
            ReaderPageTurnResult.NoChange,
            readerPageTurnResult(window.items, currentIndex = 2, delta = 1),
        )
    }

    @Test
    fun `unprepared previous chapter stops at its boundary`() {
        val window =
            buildReaderChapterWindow(
                active = chapter("chapter-2", index = 1),
                previous = adjacent(ReaderTransitionDirection.PREVIOUS, chapter("chapter-1", 0)),
                next = null,
            )

        assertEquals(
            listOf("boundary", "chapter-2:0", "chapter-2:1"),
            window.items.map(::label),
        )
        assertEquals(
            ReaderPageTurnResult.NoChange,
            readerPageTurnResult(window.items, currentIndex = 0, delta = -1),
        )
    }

    @Test
    fun `reader window updates wait until the pager is idle`() {
        assertTrue(canAdoptReaderChapterWindow(pagerIsScrolling = false))
        assertEquals(false, canAdoptReaderChapterWindow(pagerIsScrolling = true))
    }

    @Test
    fun `forward chapter commit keeps the settled page as the only target`() {
        val before =
            buildReaderChapterWindow(
                active = chapter("chapter-2", index = 1),
                previous = null,
                next = adjacent(ReaderTransitionDirection.NEXT, chapter("chapter-3", 2), true),
            )
        val after =
            buildReaderChapterWindow(
                active = chapter("chapter-3", index = 2),
                previous = adjacent(ReaderTransitionDirection.PREVIOUS, chapter("chapter-2", 1), true),
                next = null,
            )

        val result = readerChapterWindowAdoption(before, currentIndex = 3, after, startPage = 0)

        assertEquals(3, result.targetIndex)
        assertTrue(result.anchoredToCurrentKey)
        assertEquals(false, result.requiresExplicitScroll)
    }

    @Test
    fun `backward chapter commit keeps the settled page instead of applying stale start page`() {
        val before =
            buildReaderChapterWindow(
                active = chapter("chapter-3", index = 2),
                previous = adjacent(ReaderTransitionDirection.PREVIOUS, chapter("chapter-2", 1), true),
                next = null,
            )
        val after =
            buildReaderChapterWindow(
                active = chapter("chapter-2", index = 1),
                previous = null,
                next = adjacent(ReaderTransitionDirection.NEXT, chapter("chapter-3", 2), true),
            )

        val result = readerChapterWindowAdoption(before, currentIndex = 1, after, startPage = 1)

        assertEquals(1, result.targetIndex)
        assertTrue(result.anchoredToCurrentKey)
        assertEquals(false, result.requiresExplicitScroll)
    }

    @Test
    fun `prepend shifts the settled key once and does not select the active start page`() {
        val before =
            buildReaderChapterWindow(
                active = chapter("chapter-2", index = 1),
                previous = null,
                next = null,
            )
        val after =
            buildReaderChapterWindow(
                active = chapter("chapter-2", index = 1),
                previous = adjacent(ReaderTransitionDirection.PREVIOUS, chapter("chapter-1", 0), true),
                next = null,
            )

        val result = readerChapterWindowAdoption(before, currentIndex = 1, after, startPage = 0)

        assertEquals(4, result.targetIndex)
        assertTrue(result.anchoredToCurrentKey)
        assertTrue(result.targetIndex != result.fallbackIndex)
        assertEquals(false, result.requiresExplicitScroll)
    }

    @Test
    fun `previous and next directions use symmetric ordering`() {
        val current = chapter("chapter-2", index = 1)
        val window =
            buildReaderChapterWindow(
                active = current,
                previous = adjacent(ReaderTransitionDirection.PREVIOUS, chapter("chapter-1", 0), true),
                next = adjacent(ReaderTransitionDirection.NEXT, chapter("chapter-3", 2), true),
            )

        assertEquals(
            listOf(
                "chapter-1:0",
                "chapter-1:1",
                "boundary",
                "chapter-2:0",
                "chapter-2:1",
                "boundary",
                "chapter-3:0",
                "chapter-3:1",
            ),
            window.items.map(::label),
        )
    }

    @Test
    fun `chapter page mapping accounts for prepended window items`() {
        val window =
            buildReaderChapterWindow(
                active = chapter("chapter-2", index = 1),
                previous =
                    adjacent(
                        ReaderTransitionDirection.PREVIOUS,
                        chapter("chapter-1", 0),
                        prepared = true,
                    ),
                next = null,
            )

        assertEquals(1, readerWindowIndexForChapterPage(null, "chapter-2", pageIndex = 1))
        assertEquals(4, readerWindowIndexForChapterPage(window, "chapter-2", pageIndex = 1))
        assertEquals(-1, readerWindowIndexForChapterPage(window, "chapter-2", pageIndex = 9))
        assertEquals(-1, readerWindowIndexForChapterPage(window, null, pageIndex = 1))
    }

    @Test
    fun `chapter commits only after an adjacent real page settles`() {
        val current = chapter("chapter-1", index = 0)
        val next = chapter("chapter-2", index = 1)
        val window =
            buildReaderChapterWindow(
                active = current,
                previous = null,
                next = adjacent(ReaderTransitionDirection.NEXT, next, prepared = true),
            )
        val boundary = window.items[2]
        val firstNextPage = window.items[3]

        assertEquals(ReaderSettledPageEffect.None, readerSettledPageEffect("chapter-1", boundary))
        assertEquals(
            ReaderSettledPageEffect.CommitChapter("chapter-2", chapterIndex = 1, pageIndex = 0),
            readerSettledPageEffect("chapter-1", firstNextPage),
        )
    }

    @Test
    fun `natural next chapter sequence requires two page turns`() {
        val window =
            buildReaderChapterWindow(
                active = chapter("chapter-1", index = 0),
                previous = null,
                next = adjacent(ReaderTransitionDirection.NEXT, chapter("chapter-2", 1), true),
            )

        assertEquals(ReaderPageTurnResult.MoveTo(2), readerPageTurnResult(window.items, 1, 1))
        assertEquals(
            ReaderSettledPageEffect.None,
            readerSettledPageEffect("chapter-1", window.items[2]),
        )
        assertEquals(ReaderPageTurnResult.MoveTo(3), readerPageTurnResult(window.items, 2, 1))
        assertEquals(
            ReaderSettledPageEffect.CommitChapter("chapter-2", 1, 0),
            readerSettledPageEffect("chapter-1", window.items[3]),
        )
    }

    @Test
    fun `natural previous chapter sequence enters its last page`() {
        val window =
            buildReaderChapterWindow(
                active = chapter("chapter-2", index = 1),
                previous = adjacent(ReaderTransitionDirection.PREVIOUS, chapter("chapter-1", 0), true),
                next = null,
            )

        assertEquals(ReaderPageTurnResult.MoveTo(2), readerPageTurnResult(window.items, 3, -1))
        assertEquals(ReaderPageTurnResult.MoveTo(1), readerPageTurnResult(window.items, 2, -1))
        assertEquals(
            ReaderSettledPageEffect.CommitChapter("chapter-1", 0, 1),
            readerSettledPageEffect("chapter-2", window.items[1]),
        )
    }

    private fun chapter(
        id: String,
        index: Int = 0,
        revision: String = "rev-a",
    ) =
        ReaderWindowChapter(
            chapterId = id,
            chapterIndex = index,
            chapterRevision = revision,
            pageIdentities = listOf("page-1", "page-2"),
            payload = id,
        )

    private fun adjacent(
        direction: ReaderTransitionDirection,
        target: ReaderWindowChapter<String>,
        prepared: Boolean = false,
    ) =
        ReaderWindowAdjacent(
            direction = direction,
            targetChapterId = target.chapterId,
            transition =
                ReaderChapterTransition(
                    direction = direction,
                    chapterIndex = target.chapterIndex,
                    chapterLabel = "chapter",
                    title = target.chapterId,
                    status = if (prepared) ReaderTransitionStatus.Ready else ReaderTransitionStatus.Loading,
                ),
            preparedChapter = target.takeIf { prepared },
        )

    private fun label(item: ReaderChapterWindowItem<String>): String =
        when (item) {
            is ReaderChapterWindowItem.Page -> "${item.chapter.chapterId}:${item.pageIndex}"
            is ReaderChapterWindowItem.Boundary -> "boundary"
        }
}
